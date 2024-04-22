package aeonics.manager.impl;

import java.io.Closeable;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import aeonics.manager.Executor;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Timeout;
import aeonics.manager.Executor.Task;
import aeonics.template.Template;

public class DefaultTimeout extends Manager<Timeout>
{
	private static class Implementation extends Timeout implements Closeable
	{
		private final Object locker = new Object();
		private Queue<Tracker<?>> targets = new ConcurrentLinkedQueue<Timeout.Tracker<?>>();
		
		public <T> void watch(Tracker<T> tracker)
		{
			if( tracker == null ) return;
			targets.add(tracker);
			refresh();
		}
		
		public <T> void remove(Tracker<T> tracker)
		{
			if( tracker != null ) targets.remove(tracker);
		}
		
		public void refresh()
		{
			synchronized(locker)
			{
				locker.notify();
			}
		}
		
		public void close() { task.cancel(); }
		
		private Task<Void> task = Manager.of(Executor.class).background(() -> 
		{
			Thread.currentThread().setName(Thread.currentThread().getName() + " :: Timeout Manager");
			while(true)
			{
				long at = -1;
				
				Iterator<Tracker<?>> i = targets.iterator();
				while( i.hasNext() )
				{
					@SuppressWarnings("unchecked")
					Tracker<Object> t = (Tracker<Object>) i.next();
					if( t == null ) { i.remove(); continue; }
					
					try
					{
						long d = t.delay();
						if( d < 0 ) { i.remove(); continue; }
						if( d > 0 ) { if( at == -1 || (System.currentTimeMillis() + d) < at ) at = System.currentTimeMillis() + d; continue; }
						// d == 0
						Object target = t.target();
						if( target != null ) t.onExpire().trigger(target);
						i.remove();
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).fine(Timeout.class, e);
						i.remove();
					}
				}
				
				try
				{
					synchronized(locker)
					{
						if( at < 0 )
						{
							Manager.of(Logger.class).finest(Timeout.class, "No future elements to watch. Sleeping until further notice.");
							locker.wait();
						}
						else
						{
							long ms = at - System.currentTimeMillis();
							if( ms <= 0 ) continue;
							
							Manager.of(Logger.class).finest(Timeout.class, "Next timeout element is in {}ms. Sleeping.", ms);
							locker.wait(ms);
						}
					}
				}
				catch(InterruptedException e) { return; }
			}
		});
	}
	
	protected Class<? extends DefaultTimeout.Implementation> defaultEntity() { return DefaultTimeout.Implementation.class; }
	protected Supplier<? extends DefaultTimeout.Implementation> defaultCreator() { return DefaultTimeout.Implementation::new; }
	
	public Template<? extends Timeout> template()
	{
		return super.template()
			.summary("Non-blocking timeout manager")
			.description("This timeout manager will keep track of all trackers in a non-blocking efficient manner and will defer"
				+ "processing of expired elements to the Execution manager.");
	}
}
