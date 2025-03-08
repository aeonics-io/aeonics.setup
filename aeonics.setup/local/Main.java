package local;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.*;
import aeonics.entity.Step.Destination;
import aeonics.entity.Step.Origin;
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
import aeonics.template.Parameter;
import aeonics.util.Hardware;
import aeonics.util.Snapshotable.SnapshotMode;

public class Main extends Plugin
{
	private String adminHash = null;
	private String adminSalt = null;
	private String vaultSalt = null;
	
	public Main()
	{
		// allow the user to provide the default hash/salt for the admin password
		adminHash = System.getProperty("AEONICS_SECURITY_ADMIN_HASH");
		if( adminHash == null || adminHash.isBlank() ) adminHash = System.getenv("AEONICS_SECURITY_ADMIN_HASH");
		if( adminHash == null || adminHash.isBlank() ) adminHash = null;
		else System.clearProperty("AEONICS_SECURITY_ADMIN_HASH");
		
		adminSalt = System.getProperty("AEONICS_SECURITY_ADMIN_SALT");
		if( adminSalt == null || adminSalt.isBlank() ) adminSalt = System.getenv("AEONICS_SECURITY_ADMIN_SALT");
		if( adminSalt == null || adminSalt.isBlank() ) adminSalt = null;
		else System.clearProperty("AEONICS_SECURITY_ADMIN_SALT");
		
		vaultSalt = System.getProperty("AEONICS_SECURITY_VAULT_SALT");
		if( vaultSalt == null || vaultSalt.isBlank() ) vaultSalt = System.getenv("AEONICS_SECURITY_VAULT_SALT");
		if( vaultSalt == null || vaultSalt.isBlank() ) vaultSalt = null;
		else System.clearProperty("AEONICS_SECURITY_VAULT_SALT");
	}
	
	public String summary() { return "Default System"; }
	public String description() { return "Initializes the default managers, security settings, sets the default factory for built-in types and loads the initial snapshot if necessary."; }
	
	private <T extends Manager.Type> void manager(Class<T> type, Class<? extends Manager<T>> item, boolean strict, Data parameters)
	{
		if( Manager.of(type) == null )
		{
			try
			{
				T instance = item.getConstructor().newInstance().template().create(Data.map().put("parameters", parameters));
				instance.name(type.getSimpleName() + " Manager");
				Manager.set(type, instance);
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
				.create()
				.name("Lifecycle Manager");
			Manager.replace(Lifecycle.class, instance);
		}
		
		Lifecycle.before(Phase.LOAD, this::beforeLoad);
		Lifecycle.on(Phase.LOAD, this::onLoad);
		Lifecycle.after(Phase.LOAD, this::afterLoad);
		
		Lifecycle.before(Phase.CONFIG, this::beforeConfig);
		Lifecycle.on(Phase.CONFIG, this::onConfig);
		Lifecycle.after(Phase.CONFIG, this::afterConfig);
		
		Lifecycle.before(Phase.RUN, this::beforeRun);
		Lifecycle.on(Phase.RUN, this::onRun);
		Lifecycle.after(Phase.RUN, this::afterRun);
		
		Lifecycle.before(Phase.SHUTDOWN, this::beforeShutdown);
		Lifecycle.on(Phase.SHUTDOWN, this::onShutdown);
		Lifecycle.after(Phase.SHUTDOWN, this::afterShutdown);
		
		Snapshot.onSnapshot(this::onSnapshot);
		Snapshot.onRestore(this::onRestore);
	}
	
	private void beforeLoad()
	{
		Factory.add(new Console());
		
		manager(Config.class, DefaultConfig.class, false, null);
		
		if( Manager.of(Executor.class) == Executor.SYNCHRONOUS )
		{
			Executor instance = new DefaultExecutor().template().create().name("Executor Manager");
			Manager.replace(Executor.class, instance);
		}
	}
	
	private void onLoad()
	{
		manager(Snapshot.class, DefaultSnapshot.class, false, null);
		manager(Security.class, DefaultSecurity.class, false, null);
		manager(Vault.class, DefaultVault.class, false, Data.map().put("salt", vaultSalt));
		manager(Monitor.class, DefaultMonitor.class, false, null);
		manager(Scheduler.class, DefaultScheduler.class, false, null);
		manager(Timeout.class, DefaultTimeout.class, false, null);
		manager(Network.class, DefaultNetwork.class, false, null);
		manager(Translator.class, DefaultTranslator.class, false, null);
		
		// initialize static members
		DefaultLogger.register();
		
		vaultSalt = null;
		
		Config c = Manager.of(Config.class);
		
		c.declare(Network.class, new Parameter("backlog")
			.summary("Socket Backlog")
			.description("The maximum number of pending connections to be accepted by listenning server sockets.")
			.format(Parameter.Format.NUMBER)
			.rule(Parameter.Rule.DIGIT)
			.optional(true)
			.min(1).max(5)
			.defaultValue(50));
		
		c.declare("aeonics.setup", new Parameter("initialized")
			.summary("Default setup has been initialized")
			.description("This parameter defines if the default setup has already been initialized (true) or if it should done when starting the config phase (false)."
					+ " This is normally set by the system to detect an initial snapshot.")
			.format(Parameter.Format.BOOLEAN)
			.rule(Parameter.Rule.BOOLEAN)
			.optional(true)
			.defaultValue(false));
		
		c.declare("aeonics.manager.snapshot", new Parameter("current")
			.summary("Snapshot currently loaded")
			.description("This read-only parameter contains the name of the last restored snapshot. If set at boot time using command line parameters or environment "
				+ "variables, it defines which snapshot to load initially.")
			.format(Parameter.Format.TEXT)
			.optional(true)
			.defaultValue(null));
	}
	
	private void afterLoad()
	{
		/* nothing to do */
	}
	
	private void beforeConfig()
	{
		String snapshot = System.getProperty("AEONICS_MANAGER_SNAPSHOT_CURRENT");
		if( snapshot == null || snapshot.isBlank() ) snapshot = System.getenv("AEONICS_MANAGER_SNAPSHOT_CURRENT");
		if( snapshot == null || snapshot.isBlank() ) snapshot = Manager.of(Snapshot.class).latest();
		
		// restore the latest snapshot
		if( snapshot != null )
		{
			try { Manager.of(Snapshot.class).restore(snapshot).await(); }
			catch(Exception e) { Manager.of(Logger.class).warning(Snapshot.class, e); }
		}
		else
			Manager.of(Logger.class).fine(Snapshot.class, "No snapshot to restore");
	}
	
	private void onConfig()
	{
		if( !Manager.of(Config.class).get("aeonics.setup", "initialized").asBool() )
		{
			setupLoggerFlow();
			setupMonitorFlow();
			Manager.of(Config.class).set(Monitor.class, "enabled", true);
			Manager.of(Config.class).set("aeonics.setup", "initialized", true);
		}
		
		new Probe() {}
			.template()
			.summary("Hardware")
			.description("This probe returns the hardware CPU and RAM metrics.")
			.create()
			.source(() ->
			{
				return Hardware.export();
			})
			.name("hardware");
	}
	
	private void afterConfig()
	{
		/* nothing to do */
	}
	
	private void beforeRun()
	{
		if( Manager.of(Logger.class) == Logger.CONSOLE )
		{
			Logger instance = new DefaultLogger().template().create();
			instance.name("Logger Manager");
			Manager.replace(Logger.class, instance);
		}
		
		// set default security settings if no security is present
		if( !Registry.of(Provider.class).iterator().hasNext() )
		{
			Manager.of(Logger.class).config(Security.class, "Setting default security settings");
			
			User.Type user = new User().template().create(Data.map().put("parameters", Data.map().put("login", "admin").put("active", true)))
				.name("Admin User")
				.addRelation("roles", Role.SUPERADMIN)
				.addRelation("groups", Group.ADMINISTRATORS)
				.<User.Type>cast()
				;
			
			if( this.adminHash == null || this.adminSalt == null )
			{
				// hash/salt were not provided in system properties
				// so generate a random password and hash now
				String password = Manager.of(Security.class).randomHash();
				adminSalt = Manager.of(Security.class).randomHash();
				adminHash = Manager.of(Security.class).hash(password, adminSalt);
				
				// CAUTION : this is on purpose, send it to the console and NOT to the logger
				System.out.println("****** CAUTION ******");
				System.out.println("No default admin user hash/salt were provided. A new password was generated:");
				System.out.println("\t" + password);
				System.out.println("To reuse this password without snapshot, use these command line arguments:");
				System.out.println("\t-DAEONICS_SECURITY_ADMIN_HASH=" + adminHash + " -DAEONICS_SECURITY_ADMIN_SALT=" + adminSalt);
			}
			
			Data context = Data.map().put("username", user.id()).put("hash", adminHash).put("salt", adminSalt);
			adminHash = null; adminSalt = null;
			
			// initialize the default provider
			Provider.Type provider = new Provider.Local().template().create()
				.name("Local identity provider");
			if( provider.join(context, user) != user )
				Manager.of(Logger.class).severe(Security.class, "Default user could not join local provider");

			new Policy.Allow().template().create(Data.map().put("parameters", Data.map().put("scope", "topic")))
				.name("Allow everyone to use any topic")
				.addRelation("rule", new Rule.MatchAll().template().create().name("Match all"))
				.<Policy.Type>cast()
				;
		}
	}
	
	private void onRun()
	{
		// start all origins
		Registry.of(Step.class).forEach((e) -> {
			if( e instanceof Origin.Type )
			{
				try { if( ((Origin.Type)e).stopped() ) ((Origin.Type)e).start(); }
				catch(Exception x) { Manager.of(Logger.class).warning(e.getClass(), x); }
			}
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
		Registry.of(Step.class).forEach((e) -> {
			if( e instanceof Origin.Type )
			{
				try { if( ((Origin.Type)e).started() ) ((Origin.Type)e).stop(); }
				catch(Exception x) { Manager.of(Logger.class).warning(e.getClass(), x); }
			}
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
		Registry.all().forEach((r) ->
		{
			Data entities = Data.list();
			r.forEach((e) -> 
			{
				// we include all the entities including the SnapshotMode.NONE so that
				// we keep a trace of those.
				entities.add(e.snapshot());
			});
			if( entities.size() > 0 )
				registry.put(r.category(), entities);
		});
		data.put("registry", registry);
	}
	
	private void onRestore(Data data)
	{
		if( data == null || !data.isMap() ) return;
		
		if( !data.isEmpty("config") )
		{
			Config c = Manager.of(Config.class);
			data.get("config").entrySet().forEach((entry) -> {
				try { c.set(entry.getKey(), entry.getValue()); }
				catch(Exception e) { Manager.of(Logger.class).config(Snapshot.class, e); }
			});
		}
		
		if( !data.isEmpty("registry") )
		{
			// first clear all entities that are not internal and full snapshot
			Registry.all().forEach(r -> r.clear((e) -> !e.internal() && e.snapshotMode() == SnapshotMode.FULL));
			
			// then populate
			data.get("registry").entrySet().forEach((entry) -> {
				entry.getValue().forEach((entity) ->
				{
					try
					{
						SnapshotMode mode = SnapshotMode.valueOf(entity.asString("mode")); 
						Entity existing = Registry.of(entity.asString("category")).get(entity.asString("id"));
						
						switch(mode)
						{
							case FULL:
							{
								if( existing != null )
									throw new IllegalStateException("Cannot overwrite target entity " + entity);
								else
									Factory.create(entity);
								break;
							}
							case UPDATE:
							{
								if( existing == null )
									throw new IllegalStateException("Missing target entity " + entity);
								if( existing.snapshotMode() != SnapshotMode.UPDATE )
									throw new IllegalStateException("Snapshot mode mismatch for entity " + entity);
								else
									existing.template().update(entity, existing);
								break;
							}
							case NONE:
							{
								break;
							}
						}
					}
					catch(Exception e) { Manager.of(Logger.class).config(Snapshot.class, e); return; }
				});
			});
		}
	}
	
	private void setupLoggerFlow()
	{
		Destination.Type destination = Factory.of(Step.class).get(Console.class).create().name("Console output");
		DefaultLogger.origin().link("data", destination, "data");
		
		Factory.of(Flow.class).get(Flow.class).create()
			.step(DefaultLogger.origin(), 1, 1)
			.step(destination, 3, 3)
			.name("Logs")
			.parameter("notes", "This data flow is used to manage the system logs.")
			;
	}
	
	private void setupMonitorFlow()
	{
		Factory.of(Flow.class).get(Flow.class).create()
			.step(DefaultMonitor.origin(), 1, 1)
			.name("Monitoring")
			.parameter("notes", "This data flow is used to manage the various metrics of the system.")
			;
	}
}
