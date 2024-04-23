package aeonics.manager.impl;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import aeonics.manager.Executor;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Template;

public class DefaultExecutor extends Manager<Executor>
{
	private static class Implementation extends Executor
	{
		private UncaughtExceptionHandler fatal = new UncaughtExceptionHandler()
		{
			public void uncaughtException(Thread t, Throwable e)
			{
				try { Manager.of(Logger.class).severe(t.getName(), e); }
				catch(Throwable x) { e.printStackTrace(); x.printStackTrace(); }
			}
		};
				
		private ExecutorService priority = Executors.newSingleThreadExecutor(new ThreadFactory()
		{
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r);
				t.setUncaughtExceptionHandler(fatal);
				t.setDaemon(false);
				t.setPriority(Thread.MAX_PRIORITY);
				t.setName("Priority");
				return t;
			}
		});
		
		public <T> Task<T> priority(Supplier<T> task) { return Task.sync(task, priority); }

		private ExecutorService normal = Executors.newFixedThreadPool(Double.valueOf(Math.ceil(Runtime.getRuntime().availableProcessors()*1.0)).intValue(), new ThreadFactory()
		{
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r);
				t.setUncaughtExceptionHandler(fatal);
				t.setDaemon(false);
				t.setPriority(Thread.NORM_PRIORITY);
				t.setName("Normal");
				return t;
			}
		});

		public <T> Task<T> normal(Supplier<T> task) { return Task.async(task, normal); }

		private ExecutorService background = Executors.newCachedThreadPool(new ThreadFactory()
		{
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r);
				t.setUncaughtExceptionHandler(fatal);
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				t.setName("Background");
				return t;
			}
		});
		
		public <T> Task<T> background(Supplier<T> task) { return Task.sync(task, background); }
		
		public <T> Task<T> io(Supplier<T> task) { return normal(task); }
	}
	
	protected Class<? extends DefaultExecutor.Implementation> defaultTarget() { return DefaultExecutor.Implementation.class; }
	protected Supplier<? extends DefaultExecutor.Implementation> defaultCreator() { return DefaultExecutor.Implementation::new; }
	
	public Template<? extends Executor> template()
	{
		return super.template()
			.summary("Default runtime")
			.description("Manages the execution of all the tasks in the system. This manager treats I/O operations as normal tasks.");
	}
}
