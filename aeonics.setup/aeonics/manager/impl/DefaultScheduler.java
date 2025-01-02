package aeonics.manager.impl;

import java.io.Closeable;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.entity.Step.Origin;
import aeonics.manager.Executor;
import aeonics.manager.Lifecycle;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Scheduler;
import aeonics.manager.Scheduler.Cron;
import aeonics.template.Template;
import aeonics.util.Callback;
import aeonics.util.Snapshotable.SnapshotMode;
import aeonics.util.Tuples.Tuple;

public class DefaultScheduler extends Manager<Scheduler>
{
	public static void register()
	{
		// calling this method will force initialization of all private static members
	}
	
	private static Queue<Tuple<ZonedDateTime, Consumer<ZonedDateTime>>> once = new ConcurrentLinkedQueue<Tuple<ZonedDateTime, Consumer<ZonedDateTime>>>();
	private static final Object locker = new Object();
	
	private static final Origin.Background origin = new Origin() { }
		.target(Origin.Background.class)
		.creator(Origin.Background::new)
		.template()
		.summary("Scheduler data origin")
		.description("This data origin is used by the Scheduler to inject messages in the system.")
		.create(Data.map().put("id", "10000000-1700000000000000"))
		.internal(true)
		.snapshotMode(SnapshotMode.UPDATE)
		.name("Scheduler")
		.<Origin.Background>cast()
		.run(() ->
		{
			Thread.currentThread().setName("Background :: Scheduler Manager");
			while(true)
			{
				ZonedDateTime now = ZonedDateTime.now().withNano(0);
				ZonedDateTime next = null;
				for( Cron.Type c : Registry.of(Cron.class) )
				{
					if( c == null ) continue;
					
					ZonedDateTime future = c.next(false);
					if( future == null ) continue;
					
					if( future.isEqual(now) || future.isBefore(now) )
					{
						Manager.of(Logger.class).finest(Scheduler.class, "Task {} ({}) is executed now", c.id(), c.name());
						Manager.of(Executor.class).normal(() -> c.accept(now));
						future = c.next(true);
					}
					else
						Manager.of(Logger.class).finest(Scheduler.class, "Task {} ({}) is scheduled for {}", c.id(), c.name(), future);
					
					if( next == null || (future != null && future.isBefore(next)) )
						next = future;
				}
				
				Iterator<Tuple<ZonedDateTime, Consumer<ZonedDateTime>>> i = once.iterator();
				while( i.hasNext() )
				{
					Tuple<ZonedDateTime, Consumer<ZonedDateTime>> t = i.next();
					if( t.a.isEqual(now) || t.a.isBefore(now) )
					{
						Manager.of(Logger.class).finest(Scheduler.class, "One shot task is executed now");
						Manager.of(Executor.class).normal(() -> t.b.accept(now));
						i.remove();
						continue;
					}
					else
						Manager.of(Logger.class).finest(Scheduler.class, "One shot task is scheduled for {}", t.a);
					
					if( next == null || t.a.isBefore(next) )
						next = t.a;
				}
				
				// this should not happen but we never know
				if( next != null && next.isBefore(now) )
				{
					Manager.of(Logger.class).finest(Scheduler.class, "Next task is past due. Looping now.");
					continue;
				}
				
				try
				{
					synchronized(locker)
					{
						if( next == null )
						{
							Manager.of(Logger.class).finest(Scheduler.class, "No future tasks to perform. Sleeping until further notice.");
							locker.wait();
						}
						else
						{
							long ms = ChronoUnit.MILLIS.between(now, next);
							if( ms <= 0 ) continue;
							
							Manager.of(Logger.class).finest(Scheduler.class, "Next task scheduled at {}. Sleeping for {}ms.", next, ms);
							locker.wait(ms);
						}
					}
				}
				catch(InterruptedException e) { return; }
			}
		});
	
	private static class Implementation extends Scheduler implements Closeable
	{
		private volatile ZonedDateTime next = null;
		
		public void at(Consumer<ZonedDateTime> task, ZonedDateTime time)
		{
			ZonedDateTime now = ZonedDateTime.now().withNano(0);
			
			if( time.isBefore(now) )
			{
				Manager.of(Executor.class).priority(() -> task.accept(now));
				return;
			}
			else
				once.add(new Tuple<ZonedDateTime, Consumer<ZonedDateTime>>(time, task));
			
			if( next == null || time.isBefore(next) )
				refresh();
		}

		public void refresh()
		{
			synchronized(locker)
			{
				locker.notifyAll();
			}
		}
		
		public void close()
		{
			if( origin == null )
				return;
			
			origin.stop();
		}
	}
	
	protected Class<? extends DefaultScheduler.Implementation> defaultTarget() { return DefaultScheduler.Implementation.class; }
	protected Supplier<? extends DefaultScheduler.Implementation> defaultCreator() { return DefaultScheduler.Implementation::new; }

	@Override
	public Template<? extends Scheduler> template()
	{
		return super.template()
			.summary("Task scheduler")
			.description("This task scheduler is designed to optimize the processing power by sleeping until the next task has to run instead of waking up at regular interval. "
				+ "This allows a finer granularity in task scheduling without requiring constant checks.")
			.onCreate((data, instance) -> 
			{
				if( Manager.of(Lifecycle.class).phase() == Lifecycle.Phase.RUN )
				{
					origin.start();
				}
				else
				{
					Lifecycle.before(Lifecycle.Phase.RUN, Callback.once(() -> {
						origin.start();
					}));
				}
			});
	}
}
