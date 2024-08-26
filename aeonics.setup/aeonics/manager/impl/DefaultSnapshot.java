package aeonics.manager.impl;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
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
import aeonics.util.CheckCaller;
import aeonics.util.Functions.Consumer;

public class DefaultSnapshot extends Manager<Snapshot>
{
	private static class Implementation extends Snapshot
	{
		/**
		 * In order for {@link Snapshot.Snapshotable} entities to be able to perform a {@link CheckCaller},
		 * we use a local member method to perform the call on the handler.
		 * @param handler the handler
		 * @param data the data
		 * @throws Exception if something wrong happens
		 */
		private static void localSnapshot(Consumer<Data> handler, Data data) throws Exception { handler.accept(data); }
		
		/**
		 * In order for {@link Snapshot.Snapshotable} entities to be able to perform a {@link CheckCaller},
		 * we use a local member method to perform the call on the handler.
		 * @param handler the handler
		 * @param data the data
		 * @throws Exception if something wrong happens
		 */
		private static void localRestore(Consumer<Data> handler, Data data) throws Exception { handler.accept(data); }
		
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
			
			Manager.of(Logger.class).info(Snapshot.class, "Creating snapshot {}", name);
			long start = System.currentTimeMillis();
			
			return Manager.of(Executor.class).background(() ->
			{
				Thread.currentThread().setName(Thread.currentThread().getName() + " :: Snapshot");
				
				Data all = Data.map();
				for( Consumer<Data> handler : createCallback )
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
						localSnapshot(handler, data);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).warning(Snapshot.class, e);
					}
				}
				
				for( Map.Entry<String, Data> files : all.entrySet() )
					store.put(name + "/" + files.getKey() + ".json", files.getValue());
				
				long end = System.currentTimeMillis();
				Manager.of(Logger.class).fine(Snapshot.class, "Snapshot creation completed in " + (end-start) + "ms");
			}).then(() -> name);
		}

		public Task<Void> restore(String snapshot) 
		{
			if( store == null ) throw new IllegalStateException("Underlying storage is not set");
			if( !list().contains(snapshot) )
				throw new IllegalArgumentException("Invalid snapshot " + snapshot);
			
			Manager.of(Logger.class).info(Snapshot.class, "Restoring snapshot {}", snapshot);
			long start = System.currentTimeMillis();
			
			Collection<String> files = store.tree(snapshot);
			if( files == null || files.size() == 0 )
			{
				Manager.of(Logger.class).fine(Snapshot.class, "Snapshot {} does not contain anything to restore", snapshot);
				return Task.completed(null);
			}
			
			return Manager.of(Executor.class).background(() ->
			{
				Thread.currentThread().setName(Thread.currentThread().getName() + " :: Restore");
				
				Data all = Data.map();
				for( Consumer<Data> handler : restoreCallback )
				{
					String module = handler.getClass().getModule().getName();
					if( !files.contains(module + ".json") ) continue;
					Data data = all.get(module);
					if( !data.isMap() )
					{
						data = store.getData(snapshot + "/" + module + ".json");
						all.put(module, data);
					}
					
					try
					{
						localRestore(handler, data);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).warning(Snapshot.class, e);
					}
				}
				
				long end = System.currentTimeMillis();
				Manager.of(Logger.class).fine(Snapshot.class, "Snapshot restoration completed in " + (end-start) + "ms");
			});
		}

		public Collection<String> list()
		{
			if( store == null ) return Collections.emptyList();
			return store.tree("").stream()
				.filter((s) -> s.endsWith("/"))
				.map(name -> name.substring(0, name.length()-1))
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
			return store.tree("").contains(snapshot + (snapshot.endsWith("/") ? "" : "/"));
		}

		public String latest() 
		{
			if( store == null ) return null;
			
			return store.tree("").stream()
				.filter((name) -> name.endsWith("/"))
				.sorted(Comparator.reverseOrder())
				.map(name -> name.substring(0, name.length()-1))
				.findFirst()
				.orElse(null);
		}
		
		private Storage.Type store = null; 
		public void config(String key, Data value)
		{
			if( Config.implodeName(Snapshot.class, "path").equals(key) )
			{
				try
				{ 
					store = Factory.of(Storage.class).get(Storage.File.class)
						.build(Data.map().put("root", value))
						.name("Snapshot storage")
						.internal(true);
				}
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
