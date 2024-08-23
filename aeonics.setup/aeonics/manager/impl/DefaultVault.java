package aeonics.manager.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Entity;
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
		 * private salt for data hashing
		 */
		private String salt = null;
		
		@Override
		public void config(String key, Data value)
		{
			if( Config.implodeName(Vault.class, "salt").equals(key) )
			{
				String v = value == null ? null : value.asString();
				if( v == null || v.isBlank() ) salt = null;
				else salt = v;
			}
		}
		
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
		
		private Map<String, String> store = new HashMap<>();
		
		public Data get(String name, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '#' )
			{
				try { CheckCaller.require(this.getClass(), "get", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			
			name = obfuscate(name);
			key = obfuscate(key);
			
			String value = store.get(name);
			if( value == null ) return Data.empty();
			
			return Json.decode(Manager.of(Security.class).decrypt(value, key));
		}

		public void set(String name, Data value, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '#' )
			{
				try { CheckCaller.require(this.getClass(), "set", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			if( value == null ) value = Data.empty();
			
			name = obfuscate(name);
			key = obfuscate(key);
			
			String existing = store.get(name);
			if( existing != null )
			{
				// try to decrypt to ensure the key matches
				Manager.of(Security.class).decrypt(existing, key);
			}
			
			synchronized(store)
			{
				store.put(name, Manager.of(Security.class).encrypt(value.asString(), key));
			}
		}
		
		public void remove(String name, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '#' )
			{
				try { CheckCaller.require(this.getClass(), "remove", 0, true); }
				catch(IllegalCallerException e) { throw new SecurityException("Name reserved for owning entity."); }
			}
			
			if( key == null ) key = Manager.of(Security.class).hash(key);

			name = obfuscate(name);
			key = obfuscate(key);
			
			String value = store.get(name);
			if( value == null ) return;
			
			// try to decrypt to ensure the key matches
			Manager.of(Security.class).decrypt(value, key);
			
			synchronized(store)
			{
				store.remove(name);
			}
		}

		public Data get(String name, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			return get("#"+owner.type()+"@"+owner.id()+":"+name, owner.id());
		}

		public void set(String name, Data value, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			set("#"+owner.type()+"@"+owner.id()+":"+name, value, owner.id());
		}

		public void remove(String name, Entity owner) throws SecurityException
		{
			try { CheckCaller.require(owner.getClass(), null, 0, true); }
			catch(IllegalCallerException e) { throw new SecurityException("This method can only be called from the owning entity"); }
			
			remove("#"+owner.type()+"@"+owner.id()+":"+name, owner.id());
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
			.config(Vault.class, new Parameter("salt")
				.summary("The encryption key salt")
				.description("This is the salt used to further protect the data encryption key. Data is encrypted in all cases with a key provided "
						+ "by the entity. If a salt is set, then the encryption key is merged with that specific value which makes it impossible to "
						+ "decrypt the value even with the correct key given by the entity, unless the same salt is provided. This adds a layer of "
						+ "protection to the data encryption.")
				.format(Parameter.Format.TEXT)
				.optional(true))
			.builder((config, instance) ->
			{
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
