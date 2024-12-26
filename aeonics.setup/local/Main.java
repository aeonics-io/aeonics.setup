package local;

import aeonics.Plugin;
import aeonics.data.Data;
import aeonics.entity.*;
import aeonics.entity.basic.Console;
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
import aeonics.template.Channel;
import aeonics.template.Factory;
import aeonics.template.Parameter;
import aeonics.util.Hardware;

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
		setupMonitorFlow();
		
		if( !Manager.of(Config.class).get("aeonics.setup", "initialized").asBool() )
		{
			setupLoggerFlow();
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
				System.err.println("****** CAUTION ******");
				System.err.println("No default admin user hash/salt were provided. A new password was generated:");
				System.err.println("\t" + password);
				System.err.println("To reuse this password without snapshot, use these command line arguments:");
				System.err.println("\t-DAEONICS_SECURITY_ADMIN_HASH=" + adminHash + " -DAEONICS_SECURITY_ADMIN_SALT=" + adminSalt);
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
			// first clear all non-internal entities
			Registry.all().forEach(r -> r.clear(e -> !e.internal()));
			
			// then populate
			data.get("registry").entrySet().forEach((entry) -> {
				entry.getValue().forEach((entity) -> {
					try { Factory.create(entity); }
					catch(Exception e) { Manager.of(Logger.class).config(Snapshot.class, e); }
				});
			});
		}
	}
	
	private void setupLoggerFlow()
	{
		// ===========================
		// Logger topic
		// ===========================
		
		Topic.Type topic = new Topic()
			.template()
			.create()
			.name("log");
		Queue.Type queue = new Queue()
			.template()
			.create()
			.name("Logs queue");
		Destination.Type destination = Factory.of(Destination.class).get(Console.class).create().name("Console output");
		topic.addRelation("queues", queue, Data.map().put("binding", "#"));
		queue.addRelation("destinations", destination, Data.map().put("input", "data"));
		
		Factory.of(Flow.class).get(Flow.class).create()
			.addRelation("topics", topic, Data.map().put("x", 1).put("y", 1))
			.addRelation("queues", queue, Data.map().put("x", 1).put("y", 3))
			.addRelation("destinations", destination, Data.map().put("x", 3).put("y", 3))
			.name("Logs")
			.parameter("notes", "This data flow is used to manage the system logs.")
			;
	}
	
	private void setupMonitorFlow()
	{
		// ===========================
		// Monitor topic
		// ===========================
		if( !Manager.of(Config.class).get("aeonics.setup", "initialized").asBool() )
		{
			new Topic()
				.template()
				.create()
				.name("monitor");
			
			Manager.of(Config.class).set(Monitor.class, "enabled", true);
		}
		
		Origin.Type origin = new Origin.Scheduled()
			.template()
			.output(new Channel("metrics")
				.summary("Metrics")
				.description("System metrics"))
			.output(new Channel("probes")
				.summary("Probes")
				.description("System probes"))
			.summary("Monitoring data origin")
			.description("This origin entity collects monioring metrics at the interval defined by the monitor manager and feeds them as data in the system.")
			.create(Data.map().put("parameters", Data.map().put("rule", "RRULE:FREQ=SECONDLY;INTERVAL=" + (Manager.of(Config.class).get(Monitor.class, "window").asLong() / 1000))))
			.name("Monitor input");
		origin
			.<Origin.Scheduled.Type>cast()
			.task((time) -> 
			{
				if( !Manager.of(Config.class).get(Monitor.class, "enabled").asBool() ) return;
				
				try
				{
					Data monitor = Manager.of(Monitor.class).report();
					if( !monitor.isEmpty() )
						origin.emit(new Message("metrics").user(User.SYSTEM.id()).content(monitor), "metrics");
				}
				catch(Exception e)
				{
					Manager.of(Logger.class).warning(Monitor.class, e);
				}
				
				try
				{
					Data probes = Data.map();
					for( Probe.Type p : Registry.of(Probe.class) )
						probes.put(p.name(), p.report());
					if( !probes.isEmpty() )
						origin.emit(new Message("probes").user(User.SYSTEM.id()).content(probes), "probes");
				}
				catch(Exception e)
				{
					Manager.of(Logger.class).warning(Monitor.class, e);
				}
			});
		
		Manager.of(Config.class).watch(Monitor.class, "window", (key, value) -> { 
			Factory.update(origin, Data.map().put("rule", "RRULE:FREQ=SECONDLY;INTERVAL=" + (value.asLong() / 1000))); 
		});
		
		origin.addRelation("topics", Registry.of(Topic.class).get("monitor"), Data.map().put("output", "metrics"));
		origin.addRelation("topics", Registry.of(Topic.class).get("monitor"), Data.map().put("output", "probes"));
		
		Factory.of(Flow.class).get(Flow.class).create()
			.addRelation("topics", Registry.of(Topic.class).get("monitor"), Data.map().put("x", 3).put("y", 1))
			.addRelation("origins", origin, Data.map().put("x", 1).put("y", 1))
			.name("Monitoring")
			.parameter("notes", "This data flow is used to manage the various metrics of the system.")
			;
	}
}
