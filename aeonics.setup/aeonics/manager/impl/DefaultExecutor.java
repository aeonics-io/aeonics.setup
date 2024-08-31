package aeonics.manager.impl;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Probe;
import aeonics.manager.Executor;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Template;

public class DefaultExecutor extends Manager<Executor>
{
	private static class Implementation extends Executor
	{
		private static ThreadGroup group = new ThreadGroup("aeonics");
		private static ThreadGroup priority_group = new ThreadGroup(group, "priority");
		private static ThreadGroup normal_group = new ThreadGroup(group, "normal");
		private static ThreadGroup background_group = new ThreadGroup(group, "background");
		
		private static UncaughtExceptionHandler fatal = (t, e) ->
		{
			try { Manager.of(Logger.class).severe(t.getName(), e); }
			catch(Throwable x) { e.printStackTrace(); x.printStackTrace(); }
		};
				
		private MonitoredThreadPool priority = MonitoredThreadPool.single();
		public <T> Task<T> priority(aeonics.util.Functions.Supplier<T> task) { return sync(task, priority); }
		public <T> Task<T> priorityResolved(T value) { return completed(value, priority); }
		public Task<Void> priorityFailed(Throwable error) { return failed(error, priority); }
		public Task<Void> priority(List<Task<?>> tasks) { return all(tasks, priority); }
		public boolean isPriority(Thread thread) { return thread != null && thread.getThreadGroup().equals(priority_group); }

		private MonitoredThreadPool normal = MonitoredThreadPool.fixed((int) Math.ceil(Runtime.getRuntime().availableProcessors()*1.0));
		public <T> Task<T> normal(aeonics.util.Functions.Supplier<T> task) { return async(task, normal); }
		public <T> Task<T> normalResolved(T value) { return completed(value, normal); }
		public Task<Void> normalFailed(Throwable error) { return failed(error, normal); }
		public Task<Void> normal(List<Task<?>> tasks) { return all(tasks, normal); }
		public boolean isNormal(Thread thread) { return thread != null && thread.getThreadGroup().equals(normal_group); }

		private MonitoredThreadPool background = MonitoredThreadPool.cached();
		public <T> Task<T> background(aeonics.util.Functions.Supplier<T> task) { return sync(task, background); }
		public <T> Task<T> backgroundResolved(T value) { return completed(value, background); }
		public Task<Void> backgroundFailed(Throwable error) { return failed(error, background); }
		public Task<Void> background(List<Task<?>> tasks) { return all(tasks, background); }
		public boolean isBackground(Thread thread) { return thread != null && thread.getThreadGroup().equals(background_group); }
		
		public <T> Task<T> io(aeonics.util.Functions.Supplier<T> task) { return normal(task); }
		public <T> Task<T> ioResolved(T value) { return normalResolved(value); }
		public Task<Void> ioFailed(Throwable error) { return normalFailed(error); }
		public Task<Void> io(List<Task<?>> tasks) { return normal(tasks); }
		public boolean isIo(Thread thread) { return false; }
		
		private static class MonitoredThread extends Thread
		{
			private volatile long start = 0;
			public MonitoredThread(ThreadGroup group, Runnable task) { super(group, task); }
		}
		
		private static class MonitoredThreadPool extends ThreadPoolExecutor
		{
			private LongAdder errors = new LongAdder();
			private LongAdder time = new LongAdder();
			
			public static MonitoredThreadPool single()
			{
				return new MonitoredThreadPool(1, 1, 0L, new LinkedBlockingQueue<Runnable>(), (r) ->
				{
					Thread t = new MonitoredThread(priority_group, r);
					t.setUncaughtExceptionHandler(fatal);
					t.setDaemon(false);
					t.setPriority(Thread.MAX_PRIORITY);
					t.setName("Priority");
					return t;
				});
			}
			
			public static MonitoredThreadPool fixed(int n)
			{
				return new MonitoredThreadPool(n, n, 0L, new LinkedBlockingQueue<Runnable>(), (r) ->
				{
					Thread t = new MonitoredThread(normal_group, r);
					t.setUncaughtExceptionHandler(fatal);
					t.setDaemon(false);
					t.setPriority(Thread.NORM_PRIORITY);
					t.setName("Normal");
					return t;
				});
			}
			
			public static MonitoredThreadPool cached()
			{
				return new MonitoredThreadPool(0, Integer.MAX_VALUE, 60000L, new SynchronousQueue<Runnable>(), (r) ->
				{
					Thread t = new MonitoredThread(background_group, r);
					t.setUncaughtExceptionHandler(fatal);
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY);
					t.setName("Background");
					return t;
				});
			}
			
			public MonitoredThreadPool(int min, int max, long timeout, BlockingQueue<Runnable> queue, ThreadFactory factory)
			{
				super(min, max, timeout, TimeUnit.MILLISECONDS, queue, factory);
			}
			
			@Override
			public void beforeExecute(Thread thread, Runnable task)
			{
				((MonitoredThread)thread).start = System.nanoTime();
			}
			
			@Override
			public void afterExecute(Runnable task, Throwable error)
			{
				if( error != null ) errors.increment();
				time.add(System.nanoTime() - ((MonitoredThread)Thread.currentThread()).start);
			}
			
			public Data metrics()
			{
				return Data.map()
					.put("submitted", this.getTaskCount())
					.put("completed", this.getCompletedTaskCount())
					.put("errors", errors.longValue())
					.put("time", time.longValue())
					.put("pending", this.getQueue().size())
					.put("size", this.getPoolSize());
			}
		}
	}
	
	protected Class<? extends DefaultExecutor.Implementation> defaultTarget() { return DefaultExecutor.Implementation.class; }
	protected Supplier<? extends DefaultExecutor.Implementation> defaultCreator() { return DefaultExecutor.Implementation::new; }

	@Override
	public Template<? extends Executor> template()
	{
		return super.template()
			.summary("Default runtime")
			.description("Manages the execution of all the tasks in the system. This manager treats I/O operations as normal tasks.")
			.onCreate((data, instance) ->
			{
				if( !(instance instanceof Implementation) ) return;
				Implementation i = (Implementation)instance;
				
				new Probe() {}
					.template()
					.summary("Tasks")
					.description("This probe returns metrics ("
						+ "submitted: the cumulated number of tasks that have been scheduled for execution; "
						+ "completed: the cumulated number of tasts that completed successfully or not; "
						+ "errors: the cumulated number of tasks that failed; "
						+ "time: the cumulated execution time in nanoseconds; "
						+ "pending: the current number of tasks that are pending; "
						+ "size: the current number of executor threads"
						+ ") about the different execution groups (normal, priority, background, io).")
					.create()
					.source(() ->
					{
						return Data.map()
							.put("normal", i.normal.metrics())
							.put("priority", i.priority.metrics())
							.put("background", i.background.metrics())
							.put("io", Data.map().put("size", 0).put("submitted", 0).put("completed", 0).put("pending", 0).put("errors", 0).put("time", 0));
					})
					.name("tasks");
			});
	}
}
