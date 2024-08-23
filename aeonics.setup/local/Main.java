package local;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.*;
import aeonics.entity.security.*;
import aeonics.manager.*;
import aeonics.manager.Lifecycle.Phase;
import aeonics.manager.impl.DefaultConfig;
import aeonics.manager.impl.DefaultExecutor;
import aeonics.manager.impl.DefaultLifecycle;
import aeonics.manager.impl.DefaultLogger;
import aeonics.manager.impl.DefaultMonitor;
import aeonics.manager.impl.DefaultNetwork;
import aeonics.manager.impl.DefaultScheduler;
import aeonics.manager.impl.DefaultSecurity;
import aeonics.manager.impl.DefaultSnapshot;
import aeonics.manager.impl.DefaultTimeout;
import aeonics.manager.impl.DefaultTranslator;
import aeonics.manager.impl.DefaultVault;
import aeonics.template.Factory;
import aeonics.util.Callback;

public class Main extends Plugin
{
	private String hash = null;
	private String salt = null;
	
	public Main()
	{
		// allow the user to provide the default hash/salt for the admin password
		hash = System.getProperty("AEONICS_SECURITY_ADMIN_HASH");
		if( hash == null || hash.isBlank() ) hash = null;
		else System.clearProperty("AEONICS_SECURITY_ADMIN_HASH");
		salt = System.getProperty("AEONICS_SECURITY_ADMIN_SALT");
		if( salt == null || salt.isBlank() ) salt = null;
		else System.clearProperty("AEONICS_SECURITY_ADMIN_SALT");
	}
	
	public String summary() { return "Default System"; }
	public String description() { return "Initializes the default managers, security settings, sets the default factory for built-in types and loads the initial snapshot if necessary."; }
	
	private <T extends Manager.Type> void manager(Class<T> type, Class<? extends Manager<T>> item, boolean strict)
	{
		if( Manager.of(type) == null )
		{
			try
			{
				T instance = item.getConstructor().newInstance().template().build();
				instance.name(type.getSimpleName() + " Manager");
				Registry.add(Manager.set(type, instance));
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).severe(Main.class, e);
				throw new RuntimeException("Manager creation failed");
			}
		}
		else
		{
			Manager.of(Logger.class).fine(Main.class, "Manager instance of {} already set", type);
			if( strict ) throw new IllegalStateException("Manager instance already set");
		}
	}
	
	public void start()
	{
		if( Manager.of(Lifecycle.class) == Lifecycle.NOOP )
		{
			Lifecycle instance = new DefaultLifecycle()
				.template()
				.build()
				.name("Lifecycle Manager");
			Registry.add(Manager.replace(Lifecycle.class, instance));
		}
		
		Lifecycle.before(Phase.LOAD, Callback.once(() -> beforeLoad()));
		Lifecycle.on(Phase.LOAD, Callback.once(() -> onLoad()));
		Lifecycle.after(Phase.LOAD, Callback.once(() -> afterLoad()));
		
		Lifecycle.before(Phase.CONFIG, Callback.once(() -> beforeConfig()));
		Lifecycle.on(Phase.CONFIG, Callback.once(() -> onConfig()));
		Lifecycle.after(Phase.CONFIG, Callback.once(() -> afterConfig()));
		
		Lifecycle.before(Phase.RUN, Callback.once(() -> beforeRun()));
		Lifecycle.on(Phase.RUN, Callback.once(() -> onRun()));
		Lifecycle.after(Phase.RUN, Callback.once(() -> afterRun()));
		
		Lifecycle.before(Phase.SHUTDOWN, Callback.once(() -> beforeShutdown()));
		Lifecycle.on(Phase.SHUTDOWN, Callback.once(() -> onShutdown()));
		Lifecycle.after(Phase.SHUTDOWN, Callback.once(() -> afterShutdown()));
		
		Snapshot.onSnapshot(this::onSnapshot);
		Snapshot.onRestore(this::onRestore);
	}
	
	private void beforeLoad()
	{
		// basic entities
		Factory.add(new Queue());
		Factory.add(new Storage.File());
		Factory.add(new Storage.Memory());
		Factory.add(new Topic());
		
		// security entities
		Factory.add(new Group());
		Factory.add(new Policy.Allow());
		Factory.add(new Policy.Deny());
		Factory.add(new Policy.TargetedAllow());
		Factory.add(new Policy.TargetedDeny());
		Factory.add(new Provider.Local());
		Factory.add(new Role());
		Factory.add(new Rule.And());
		Factory.add(new Rule.AskProviders());
		Factory.add(new Rule.MatchAll());
		Factory.add(new Rule.MatchAttribute());
		Factory.add(new Rule.MatchContext());
		Factory.add(new Rule.MatchNone());
		Factory.add(new Rule.Or());
		Factory.add(new Rule.Not());
		Factory.add(new Rule.Role());
		Factory.add(new Rule.Xor());
		Factory.add(new User());
		
		// managers
		manager(Config.class, DefaultConfig.class, false);
		manager(Snapshot.class, DefaultSnapshot.class, false);
		manager(Security.class, DefaultSecurity.class, false);
		manager(Vault.class, DefaultVault.class, false);
		manager(Monitor.class, DefaultMonitor.class, false);
		manager(Scheduler.class, DefaultScheduler.class, false);
		manager(Timeout.class, DefaultTimeout.class, false);
		manager(Network.class, DefaultNetwork.class, false);
		manager(Translator.class, DefaultTranslator.class, false);
		
		if( Manager.of(Executor.class) == Executor.SYNCHRONOUS )
		{
			Executor instance = new DefaultExecutor().template().build().name("Executor Manager");
			Registry.add(Manager.replace(Executor.class, instance));
		}
	}
	
	private void onLoad()
	{
		/* nothing to do */
	}
	
	private void afterLoad()
	{
		/* nothing to do */
	}
	
	private void beforeConfig()
	{
		// restore the latest snapshot
		String latestSnapshot = Manager.of(Snapshot.class).latest();
		if( latestSnapshot != null )
		{
			try { Manager.of(Snapshot.class).restore(latestSnapshot).await(); }
			catch(Exception e) { Manager.of(Logger.class).warning(Snapshot.class, e); }
		}
		else
			Manager.of(Logger.class).fine(Snapshot.class, "No snapshot to restore");
	}
	
	private void onConfig()
	{
		// enable monitoring
		Manager.of(Config.class).set(Monitor.class, "enabled", Data.of(true));
	}
	
	private void afterConfig()
	{
		/* nothing to do */
	}
	
	private void beforeRun()
	{
		if( Manager.of(Logger.class) == Logger.CONSOLE )
		{
			Logger instance = new DefaultLogger().template().build();
			instance.name("Logger Manager");
			Registry.add(Manager.replace(Logger.class, instance));
		}
		
		// set default security settings if no security is present
		if( !Registry.of(Provider.class).iterator().hasNext() )
		{
			Manager.of(Logger.class).config(Security.class, "Setting default security settings");
			
			User.Type user = new User().template().build(Data.map().put("__id", "admin").put("active", true))
				.name("Admin User")
				.addRelation("roles", Role.SUPERADMIN)
				.addRelation("groups", new Group().template().build()
					.name("Administrators")
					.addRelation("roles", new Role().template().build().name("Admin")))
				.<User.Type>cast()
				;
			
			if( this.hash == null || this.salt == null )
			{
				// hash/salt were not provided in system properties
				// so generate a random password and hash now
				String password = Manager.of(Security.class).randomHash();
				salt = Manager.of(Security.class).randomHash();
				hash = Manager.of(Security.class).hash(password, salt);
				
				// CAUTION : this is on purpose, send it to the console and NOT to the logger
				System.err.println("****** CAUTION ******");
				System.err.println("No default admin user hash/salt were provided. A new password was generated:");
				System.err.println("\t" + password);
				System.err.println("To reuse this password, use these command line arguments:");
				System.err.println("\t-DAEONICS_SECURITY_ADMIN_HASH=" + hash + " -DAEONICS_SECURITY_ADMIN_SALT=" + salt);
			}
			
			Data context = Data.map().put("username", user.id()).put("hash", hash).put("salt", salt);
			hash = null; salt = null;
			
			// initialize the default provider
			Provider.Type provider = new Provider.Local().template().build()
				.name("Local identity provider");
			if( provider.join(context, user) != user )
				Manager.of(Logger.class).severe(Security.class, "Default user could not join local provider");

			new Policy.Allow().template().build(Data.map().put("scope", "topic"))
				.name("Allow everyone to use any topic")
				.addRelation("rule", new Rule.MatchAll().template().build().name("Match all"))
				.<Policy.Type>cast()
				;
		}
	}
	
	private void onRun()
	{
		// start all origins
		Registry.of(Origin.class).forEach((e) -> {
			try { if( e.stopped() ) e.start(); }
			catch(Exception x) { Manager.of(Logger.class).warning(e.getClass(), x); }
		});
	}
	
	private void afterRun()
	{
		/* nothing to do */
	}
	
	private void beforeShutdown()
	{
		/* nothing to do */
	}
	
	private void onShutdown()
	{
		/* nothing to do */
	}
	
	private void afterShutdown()
	{
		Manager.replace(Logger.class, Logger.CONSOLE);
		
		// stop all origins
		Registry.of(Origin.class).forEach((e) -> {
			try { if( e.started() ) e.stop(); }
			catch(Exception x) { Manager.of(Logger.class).warning(e.getClass(), x); }
		});
	}
	
	private void onSnapshot(Data data)
	{
		if( data == null || !data.isMap() ) return;
		
		Data config = Data.map();
		Manager.of(Config.class).all().entrySet().forEach((entry) -> {
			String type = entry.getKey();
			entry.getValue().entrySet().forEach((subentry) -> {
				config.put(Config.implodeName(type, subentry.getKey()), subentry.getValue());
			});
		});
		data.put("config", config);
		
		Data registry = Data.map();
		Registry.all().forEach((r) -> {
			Data entities = Data.list();
			r.forEach((e) -> {
				if( !e.internal() )
					entities.add(e.snapshot());
			});
			if( entities.size() > 0 )
				registry.put(r.category(), entities);
		});
		data.put("registry", registry);
		
		// TODO : DefaultSecurity.tokens
		// TODO : DefaultVault.store
		// TODO : [BloodStream]aeonics.oidc.Common.[Code|Refresh|OTP].local
	}
	
	private void onRestore(Data data)
	{
		if( data == null || !data.isMap() ) return;
		
		if( !data.isEmpty("config") )
		{
			Config c = Manager.of(Config.class);
			data.get("config").entrySet().forEach((entry) -> {
				c.set(entry.getKey(), entry.getValue());
			});
		}
		
		if( !data.isEmpty("registry") )
		{
			data.get("registry").entrySet().forEach((entry) -> {
				Registry<?> r = Registry.of(entry.getKey());
				entry.getValue().forEach((entity) -> r.put(Factory.build(entity)));
			});
		}
	}
}
