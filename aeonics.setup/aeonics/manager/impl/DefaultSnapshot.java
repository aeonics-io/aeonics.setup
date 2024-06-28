package aeonics.manager.impl;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import aeonics.data.Data;
import aeonics.entity.Storage;
import aeonics.manager.Config;
import aeonics.manager.Executor;
import aeonics.manager.Executor.Task;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Snapshot;
import aeonics.template.Factory;
import aeonics.template.Parameter;
import aeonics.template.Template;

public class DefaultSnapshot extends Manager<Snapshot>
{
	private static class Implementation extends Snapshot
	{
		List<Consumer<Data>> snapshotHandlers = new ArrayList<>();
		public void onSnapshot(Consumer<Data> handler) 
		{
			synchronized(snapshotHandlers)
			{
				snapshotHandlers.add(handler);
			}
		}

		List<Consumer<Data>> restoreHandlers = new ArrayList<>();
		public void onRestore(Consumer<Data> handler)
		{
			synchronized(restoreHandlers)
			{
				restoreHandlers.add(handler);
			}
		}

		public Task<String> create(String suffix)
		{
			if( store == null ) throw new IllegalStateException("Underlying storage is not set");
			
			if( suffix == null ) suffix = "";
			suffix = suffix.replaceAll("[^a-zA-Z0-9]", "");
			suffix = suffix.substring(0, Math.min(30, suffix.length()));
			
			ZonedDateTime z = ZonedDateTime.now(ZoneId.of("UTC"));
			String prefix = z.getYear() + "-" 
				+ (z.getMonthValue() < 10 ? "0" : "") + z.getMonthValue() + "-" 
				+ (z.getDayOfMonth() < 10 ? "0" : "") + z.getDayOfMonth() + "T"
				+ (z.getHour() < 10 ? "0" : "") + z.getHour() + "-"
				+ (z.getMinute() < 10 ? "0" : "") + z.getMinute() + "-"
				+ (z.getSecond() < 10 ? "0" : "") + z.getSecond() + "Z";
			
			String name = prefix + "_" + suffix;
			
			return Manager.of(Executor.class).background(() ->
			{
				Thread.currentThread().setName(Thread.currentThread().getName() + " :: Snapshot");
				
				Data all = Data.map();
				for( Consumer<Data> handler : snapshotHandlers )
				{
					String module = handler.getClass().getModule().getName();
					Data data = all.get(module);
					if( !data.isMap() )
					{
						data = Data.map();
						all.put(module, data);
					}
					
					try
					{
						handler.accept(data);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).warning(Snapshot.class, e);
					}
				}
				
				for( Map.Entry<String, Data> files : all.entrySet() )
					store.put(name + File.separator + files.getKey() + ".json", files.getValue());
				
			}).then(() -> name);
		}

		public Task<Void> restore(String snapshot) 
		{
			if( store == null ) throw new IllegalStateException("Underlying storage is not set");
			if( !list().contains(snapshot) )
				throw new IllegalArgumentException("Invalid snapshot");
			
			Collection<String> files = store.tree(snapshot);
			
			return Manager.of(Executor.class).background(() ->
			{
				Thread.currentThread().setName(Thread.currentThread().getName() + " :: Restore");
				
				Data all = Data.map();
				for( Consumer<Data> handler : restoreHandlers )
				{
					String module = handler.getClass().getModule().getName();
					if( !files.contains(module + ".json") ) continue;
					Data data = all.get(module);
					if( !data.isMap() )
					{
						data = store.getData(snapshot + File.separator + module + ".json");
						all.put(module, data);
					}
					
					try
					{
						handler.accept(data);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).warning(Snapshot.class, e);
					}
				}
			});
		}

		public Collection<String> list()
		{
			if( store == null ) return Collections.emptyList();
			return store.tree("").stream()
				.filter((s) -> !s.endsWith("/"))
				.collect(Collectors.toList());
		}

		public void remove(String snapshot)
		{
			if( store == null ) throw new IllegalStateException("Underlying storage is not set");
			store.remove(snapshot);
		}

		public boolean exists(String snapshot) 
		{
			if( store == null ) return false;
			return store.tree("").contains(snapshot + (snapshot.endsWith(File.separator) ? "" : File.separator));
		}

		public String latest() 
		{
			if( store == null ) return null;
			
			String s = store.tree("").stream().sorted().reduce((first, second) -> second).orElse(null);
			if( s != null && s.endsWith(File.separator) )
				return s.substring(0, s.length()-1);
			else
				return s;
		}
		
		private Storage.Type store = null; 
		public void config(String key, Data value)
		{
			if( Config.implodeName(Snapshot.class, "path").equals(key) )
			{
				try { store = Factory.of(Storage.class).get(Storage.File.class).build(Data.map().put("root", value)).name("Snapshot storage"); }
				catch(Exception e)
				{
					Manager.of(Logger.class).warning(Snapshot.class, "Could not initialize destination storage {}", value);
					store = null;
				}
			}
		}
	}
	
	protected Class<? extends DefaultSnapshot.Implementation> defaultTarget() { return DefaultSnapshot.Implementation.class; }
	protected Supplier<? extends DefaultSnapshot.Implementation> defaultCreator() { return DefaultSnapshot.Implementation::new; }
	
	public Template<? extends Snapshot> template()
	{
		return super.template()
			.summary("Persistent snapshot")
			.description("Manages system snapshots in a specified local directory and stores data as separate JSON files for each plugin.")
			.config(Snapshot.class, new Parameter("path")
				.summary("Snapshot folder path")
				.description("The path to the snapshot folder")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue(Data.of("shapshots")));
	}
}
