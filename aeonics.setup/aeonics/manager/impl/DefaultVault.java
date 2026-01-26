package aeonics.manager.impl;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.manager.Config;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.manager.Snapshot;
import aeonics.manager.Vault;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.CheckCaller;
import aeonics.util.Json;

public class DefaultVault extends Manager<Vault>
{
	private static class Implementation extends Vault
	{
		/**
		 * This is the salt used to further protect the data encryption key. Data is encrypted in all cases with a key provided 
		 * by the entity. If a salt is set, then the encryption key is merged with that specific value which makes it impossible to 
		 * decrypt the value even with the correct key given by the entity, unless the same salt is provided. This adds a layer of 
		 * protection to the data encryption.
		 * 
		 * This value can only be set by the template builder function.
		 */
		private String salt = null;
		
		/**
		 * Generates a combined version of the key using the private salt.
		 * If the salt is null, the key is returned as-is
		 * @param key the key
		 * @return the salted key
		 */
		private String obfuscate(String key)
		{
			if( salt == null || key == null ) return key;
			return Manager.of(Security.class).hash(key, salt);
		}
		
		private Map<String, String> store = new ConcurrentHashMap<>();
		
		public Data get(String name, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '_' )
			{
				try { CheckCaller.require(this.getClass(), "get", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			
			name = obfuscate(name);
			key = obfuscate(key);
			
			Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Vault.class, "storage").asString());
			String value = null;
			if( storage == null )
				value = store.get(name);
			else
				value = storage.getString("vault/" + name);
			if( value == null ) return Data.empty();
			
			return Json.decode(Manager.of(Security.class).decrypt(value, key));
		}

		public void set(String name, Data value, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '_' )
			{
				try { CheckCaller.require(this.getClass(), "set", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			if( value == null ) value = Data.empty();
			
			name = obfuscate(name);
			key = obfuscate(key);
			
			Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Vault.class, "storage").asString());
			String existing = null;
			if( storage == null )
				existing = store.get(name);
			else
				existing = storage.getString("vault/" + name);
			if( existing != null )
			{
				// try to decrypt to ensure the key matches
				Manager.of(Security.class).decrypt(existing, key);
			}
			
			synchronized(store)
			{
				if( storage == null )
					store.put(name, Manager.of(Security.class).encrypt(value.asString(), key));
				else
					storage.put("vault/" + name, Manager.of(Security.class).encrypt(value.asString(), key));
			}
		}
		
		public void remove(String name, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '_' )
			{
				try { CheckCaller.require(this.getClass(), "remove", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);

			name = obfuscate(name);
			key = obfuscate(key);
			
			Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Vault.class, "storage").asString());
			String value = null;
			if( storage == null )
				value = store.get(name);
			else
				value = storage.getString("vault/" + name);
			if( value == null ) return;
			
			// try to decrypt to ensure the key matches
			Manager.of(Security.class).decrypt(value, key);
			
			synchronized(store)
			{
				if( storage == null )
					store.remove(name);
				else
					storage.remove("vault/" + name);
			}
		}

		public Data get(String name, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			return get("_"+owner.type()+"_"+owner.id()+"_"+name, owner.id());
		}

		public void set(String name, Data value, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			set("_"+owner.type()+"_"+owner.id()+"_"+name, value, owner.id());
		}

		public void remove(String name, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			remove("_"+owner.type()+"_"+owner.id()+"_"+name, owner.id());
		}
	}
	
	protected Class<? extends DefaultVault.Implementation> defaultTarget() { return DefaultVault.Implementation.class; }
	protected Supplier<? extends DefaultVault.Implementation> defaultCreator() { return DefaultVault.Implementation::new; }
	
	@Override
	public Template<? extends Vault> template()
	{
		return super.template()
			.summary("Simple vault")
			.description("This vault implementation stores data in memory and offers class instance access protection.")
			.config(Vault.class, new Parameter("storage")
				.summary("Storage")
				.description("The name or id of the storage for encrypted data. If the storage does not exist, a local temporary (ouf-of-storage) location is used instead.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue(Data.empty()))
			.onCreate((config, instance) ->
			{
				// undocumented parameter on purpose
				// so that it does not get snapshotted and is not readdable or 
				// settable other than from here
				if( config.containsKey("salt") )
					((Implementation)instance).salt = config.asString("salt");
					
				Snapshot.onRestore((data) ->
				{
					if( !(Manager.of(Vault.class) instanceof Implementation) ) return;
					if( data == null || !data.isMap() || !data.isMap("vault") || data.isEmpty("vault") ) return;
					
					Implementation vault = (Implementation) Manager.of(Vault.class);
					vault.store.clear();
					data.get("vault").entrySet().forEach((entry) -> 
					{
						vault.store.put(entry.getKey(), entry.getValue().asString());
					});
				});
				
				Snapshot.onSnapshot((data) ->
				{
					if( !(Manager.of(Vault.class) instanceof Implementation) ) return;
					if( data == null || !data.isMap() ) return;
					
					Data d = Data.map();
					Implementation vault = (Implementation) Manager.of(Vault.class);
					vault.store.entrySet().forEach((entry) -> 
					{
						d.put(entry.getKey(), entry.getValue());
					});
					
					data.put("vault", d);
				});
			});
	}
}
