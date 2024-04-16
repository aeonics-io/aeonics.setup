package aeonics.manager.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import aeonics.Boot;
import aeonics.manager.Lifecycle;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Template;
import aeonics.util.StringUtils;
import aeonics.util.Callback.Once;

public class DefaultLifecycle extends Manager<Lifecycle>
{
	private static class Implementation extends Lifecycle
	{
		private AtomicReference<Phase> current = new AtomicReference<>(null);
		
		public void boot() 
		{
			if( Thread.currentThread() != Boot.MAIN )
				throw new IllegalStateException("This method must be called from the main thread");
			if( !current.compareAndSet(null, Phase.LOAD) )
				throw new IllegalStateException("This method cannot be called more than once");
			
			start(Phase.LOAD);
			start(Phase.CONFIG);
			start(Phase.RUN);
			
			System.gc();
			Manager.of(Logger.class).info(Boot.class, "System boot-to-run in {}ms. Ready.", (System.currentTimeMillis() - Boot.BOOT_TIME));
			
			AtomicBoolean terminated = new AtomicBoolean(false);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				if( !terminated.get() )
					Boot.MAIN.interrupt();
				while( !terminated.get() )
					LockSupport.parkNanos(1_000);
			}));
			
			while( !Boot.MAIN.isInterrupted() )
			{
				try { synchronized(Boot.MAIN) { Boot.MAIN.wait(); } }
				catch(InterruptedException ie) { break; }
			}
			
			start(Phase.SHUTDOWN);
			terminated.set(true);
		}
		
		private void start(Phase phase)
		{
			current.set(phase);
			Thread.currentThread().setName("Main :: " + current);
			
			long start = System.currentTimeMillis();
			Manager.of(Logger.class).finer(Lifecycle.class, "Phase " + phase + " initiated");
	
			List<Once<Void>> b = before.get(phase);
			if( b == null ) b = Collections.emptyList();
			List<Once<Void>> o = on.get(phase);
			if( o == null ) o = Collections.emptyList();
			List<Once<Void>> a = after.get(phase);
			if( a == null ) a = Collections.emptyList();
			
			for( List<Once<Void>> step : List.of(b, o, a) )
			{
				Iterator<Once<Void>> i = step.iterator();
				Once<Void> h = null;
				
				while( i.hasNext() )
				{
					try
					{
						h = i.next();
						i.remove();
						
						h.accept(null);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).warning(Lifecycle.class, e);
					}
				}
			}

			long end = System.currentTimeMillis();
			Manager.of(Logger.class).fine(Lifecycle.class, "Phase " + phase + " completed in " + (end-start) + "ms");
		}
		
		Map<Phase, List<Once<Void>>> before = new ConcurrentHashMap<>();
		public void before(Phase phase, Once<Void> handler) 
		{
			synchronized(phase)
			{
				if( !before.containsKey(phase) )
					before.put(phase, new ArrayList<>());
				before.get(phase).add(handler);
			}
		}

		Map<Phase, ArrayList<Once<Void>>> on = new ConcurrentHashMap<>();
		public void on(Phase phase, Once<Void> handler) 
		{
			synchronized(phase)
			{
				if( !on.containsKey(phase) )
					on.put(phase, new ArrayList<>());
				on.get(phase).add(handler);
			}
		}

		Map<Phase, ArrayList<Once<Void>>> after = new ConcurrentHashMap<>();
		public void after(Phase phase, Once<Void> handler)
		{
			synchronized(phase)
			{
				if( !after.containsKey(phase) )
					after.put(phase, new ArrayList<>());
				after.get(phase).add(handler);
			}
		}
	}
	
	private static Template<Implementation> template = new Template<Implementation>(Implementation.class, StringUtils.toLowerCase(Lifecycle.class), StringUtils.toLowerCase(Manager.class))
		.creator(Implementation::new)
		.summary("Application Lifecycle")
		.description("Manages the dispatching of application-wide lifecycle events.")
		;
		
	public Template<? extends Lifecycle> template() { return template; }
	public Class<? extends Lifecycle> entity() { return Implementation.class; }
}
