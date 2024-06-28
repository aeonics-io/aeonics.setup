package aeonics.manager.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import aeonics.Boot;
import aeonics.manager.Lifecycle;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Template;

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
	
			try
			{
				before(phase).trigger(null).await();
				on(phase).trigger(null).await();
				after(phase).trigger(null).await();
				
				long end = System.currentTimeMillis();
				Manager.of(Logger.class).fine(Lifecycle.class, "Phase " + phase + " completed in " + (end-start) + "ms");
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).severe(Lifecycle.class, "Phase " + phase + " failed unexpectedly");
				Manager.of(Logger.class).fine(Lifecycle.class, e);
			}
		}
	}
	
	protected Class<? extends DefaultLifecycle.Implementation> defaultTarget() { return DefaultLifecycle.Implementation.class; }
	protected Supplier<? extends DefaultLifecycle.Implementation> defaultCreator() { return DefaultLifecycle.Implementation::new; }

	@Override
	public Template<? extends Lifecycle> template()
	{
		return super.template()
			.summary("Application Lifecycle")
			.description("Manages the dispatching of application-wide lifecycle events.");
	}
}
