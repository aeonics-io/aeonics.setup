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
	// TODO : snapshot boot + snapshot/restore registry
	
	public Main()
	{
		// this is the only exception allowed in the plugin constructor
		// because we need to set the lifecycle manager before the start() 
		// method is called on any (other/this) plugin
		
		manager(Lifecycle.class, DefaultLifecycle.class, true);
	}
	
	public String summary() { return "Default System"; }
	public String description() { return "Initializes the default managers, security settings, sets the default factory for built-in types and loads the initial snapshot if necessary."; }
	
	private <T extends Manager.Type> void manager(Class<T> type, Class<? extends Manager<T>> item, boolean strict)
	{
		if( Manager.of(type) == null )
		{
			try
			{
				T instance = Factory.add(item.getConstructor().newInstance()).build(null);
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
		Manager.of(Lifecycle.class).before(Phase.LOAD, Callback.once(() -> beforeLoad()));
		Manager.of(Lifecycle.class).on(Phase.LOAD, Callback.once(() -> onLoad()));
		Manager.of(Lifecycle.class).after(Phase.LOAD, Callback.once(() -> afterLoad()));
		
		Manager.of(Lifecycle.class).before(Phase.CONFIG, Callback.once(() -> beforeConfig()));
		Manager.of(Lifecycle.class).on(Phase.CONFIG, Callback.once(() -> onConfig()));
		Manager.of(Lifecycle.class).after(Phase.CONFIG, Callback.once(() -> afterConfig()));
		
		Manager.of(Lifecycle.class).before(Phase.RUN, Callback.once(() -> beforeRun()));
		Manager.of(Lifecycle.class).on(Phase.RUN, Callback.once(() -> onRun()));
		Manager.of(Lifecycle.class).after(Phase.RUN, Callback.once(() -> afterRun()));
		
		Manager.of(Lifecycle.class).before(Phase.SHUTDOWN, Callback.once(() -> beforeShutdown()));
		Manager.of(Lifecycle.class).on(Phase.SHUTDOWN, Callback.once(() -> onShutdown()));
		Manager.of(Lifecycle.class).after(Phase.SHUTDOWN, Callback.once(() -> afterShutdown()));
	}
	
	private void beforeLoad()
	{
		manager(Config.class, DefaultConfig.class, false);
	}
	
	private void onLoad()
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
		Factory.add(new Rule.Xor());
		Factory.add(new User());
	}
	
	private void afterLoad()
	{
	}
	
	private void beforeConfig()
	{
		manager(Executor.class, DefaultExecutor.class, false);
		manager(Snapshot.class, DefaultSnapshot.class, false);
		manager(Vault.class, DefaultVault.class, false);
	}
	
	private void onConfig()
	{
	}
	
	private void afterConfig()
	{
		manager(Monitor.class, DefaultMonitor.class, false);
		manager(Scheduler.class, DefaultScheduler.class, false);
		manager(Timeout.class, DefaultTimeout.class, false);
		manager(Network.class, DefaultNetwork.class, false);
		manager(Security.class, DefaultSecurity.class, false);
		manager(Translator.class, DefaultTranslator.class, false);
	}
	
	private void beforeRun()
	{
		Manager.of(Snapshot.class).onSnapshot((data) -> onSnapshot(data));
		Manager.of(Snapshot.class).onSnapshot((data) -> onRestore(data));
		
		if( Manager.of(Logger.class) == Logger.CONSOLE )
		{
			Logger instance = new DefaultLogger().template().build(null);
			instance.name("Logger Manager");
			Registry.add(Manager.replace(Logger.class, instance));
		}
		
		if( !Registry.of(Provider.class).iterator().hasNext() )
		{
			Manager.of(Logger.class).config(Security.class, "Setting default security settings");
			
			Provider.Type provider = Registry.of(Provider.class).put(Factory.of(Provider.class).get(Provider.Local.class).build(null));
			provider.name("Local password-based identity provider");
			
			Role.Type role = Registry.of(Role.class).put(Factory.of(Role.class).get(Role.class).build(null));
			role.name("Administrator");
			
			Group.Type group = Registry.of(Group.class).put(Factory.of(Group.class).get(Group.class).build(null));
			group.name("Administrators");
			group.addRelation("roles", role);
			
			User.Type user = Registry.of(User.class).put(Factory.of(User.class).get(User.class).build(Data.map().put("__id", "admin").put("active", true)));
			user.name("Admin User");
			user.addRelation("groups", group);
			user.addRelation("roles", role);
			
			provider.authenticate(Data.map().put("username", user.id()).put("password", "admin"));

			Rule.Type rule = Registry.of(Rule.class).put(Factory.of(Rule.class).get(Rule.MatchAll.class).build(null));
			rule.name("Match all");
			
			Policy.Type policy = Registry.of(Policy.class).put(Factory.of(Policy.class).get(Policy.Allow.class).build(Data.map().put("scope", "topic")));
			policy.name("Allow everyone to use any topic");
			policy.addRelation("rule", rule);
		}
	}
	
	private void onRun()
	{
		
	}
	
	private void afterRun()
	{
		
	}
	
	private void beforeShutdown()
	{
		
	}
	
	private void onShutdown()
	{
		
	}
	
	private void afterShutdown()
	{
		
	}
	
	private void onSnapshot(Data data)
	{
		
	}
	
	private void onRestore(Data data)
	{
		
	}
}
