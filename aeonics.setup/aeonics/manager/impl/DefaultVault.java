package aeonics.manager.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Entity;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.manager.Vault;
import aeonics.template.Template;

public class DefaultVault extends Manager<Vault>
{
	private static class Implementation extends Vault
	{
		private Map<String, String> store = new HashMap<>();
		
		public Data get(String name, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '#' && StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass() != this.getClass() )
				throw new SecurityException("Name reserved for owning entity.");
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			
			String value = store.get(name);
			if( value == null ) return Data.empty();
			
			return Data.of(Manager.of(Security.class).decrypt(value, key));
		}

		public void set(String name, Data value, String key) throws SecurityException
		{
			Objects.requireNonNull(name);
			
			if( name.length() > 0 && name.charAt(0) == '#' && StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass() != this.getClass() )
				throw new SecurityException("Name reserved for owning entity.");
			
			if( key == null ) key = Manager.of(Security.class).hash(key);
			if( value == null ) value = Data.empty();
			
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
			
			if( name.length() > 0 && name.charAt(0) == '#' && StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass() != this.getClass() )
				throw new SecurityException("Name reserved for owning entity.");
			
			String value = store.get(name);
			if( value == null ) return;
			if( key == null ) key = Manager.of(Security.class).hash(key);
			
			// try to decrypt to ensure the key matches
			Manager.of(Security.class).decrypt(value, key);
			
			synchronized(store)
			{
				store.remove(name);
				return;
			}
		}

		public Data get(String name, Entity owner) throws SecurityException
		{
			StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
			if( walker.getCallerClass() != owner.getClass() )
				throw new SecurityException("This method can only be called from the owning entity");
			return get("#"+owner.type()+"@"+owner.id()+":"+name, owner.id());
		}

		public void set(String name, Data value, Entity owner) throws SecurityException
		{
			StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
			if( walker.getCallerClass() != owner.getClass() )
				throw new SecurityException("This method can only be called from the owning entity");
			set("#"+owner.type()+"@"+owner.id()+":"+name, value, owner.id());
		}

		public void remove(String name, Entity owner) throws SecurityException
		{
			StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
			if( walker.getCallerClass() != owner.getClass() )
				throw new SecurityException("This method can only be called from the owning entity");
			remove("#"+owner.type()+"@"+owner.id()+":"+name, owner.id());
		}
	}
	
	protected Class<? extends DefaultVault.Implementation> defaultTarget() { return DefaultVault.Implementation.class; }
	protected Supplier<? extends DefaultVault.Implementation> defaultCreator() { return DefaultVault.Implementation::new; }
	
	public Template<? extends Vault> template()
	{
		return super.template()
			.summary("Simple vault")
			.description("This vault implementation stores data in memory and offers basic type token access protection.");
	}
}
