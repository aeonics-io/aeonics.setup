package aeonics.manager.impl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.manager.Config;
import aeonics.manager.Manager;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Callback;
import aeonics.util.StringUtils;
import aeonics.util.Tuple;
import aeonics.util.Functions.BiConsumer;

public class DefaultConfig extends Manager<Config>
{
	private static class Implementation extends Config
	{
		private Map<String, Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>>> store = new HashMap<>(); 
		
		public void declare(String type, Parameter parameter)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(parameter);
			
			synchronized(store)
			{
				String key = implodeName(type, parameter.name());
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.get(key);
				if( value == null )
					store.put(key, new Tuple<>(parameter.defaultValue(), new Tuple<>(null, parameter)));
				else value.b.b = parameter;
			}
		}

		public Data get(String type, String name)
		{
			if( type == null || type.isBlank() || name == null || name.isBlank() ) return Data.empty();
			
			String key = implodeName(type, name);
			Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.get(key);
			if( value == null ) return null;
			return value.b.b.resolve(value.a, null);
		}
		
		public boolean contains(String type, String name)
		{
			if( type == null || type.isBlank() || name == null || name.isBlank() ) return false;
			
			String key = implodeName(type, name);
			return store.containsKey(key);
		}

		public Data set(String type, String name, Data value)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(name);
			
			synchronized(store)
			{
				String key = implodeName(type, name);
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> v = store.computeIfAbsent(key, (k) -> new Tuple<>(null, new Tuple<>(null, new Parameter(name.toLowerCase(Locale.ROOT).replace('_', '.')).optional(true))));
				if( !v.b.b.validate(value) ) throw new IllegalArgumentException("Invalid value for parameter " + key);
				Data old = v.a;
				v.a = value;
				
				if( v.b.a != null ) v.b.a.trigger(Tuple.of(key, value));
				return old;
			}
		}

		public Data remove(String type, String name)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(name);
			
			synchronized(store)
			{
				String key = implodeName(type, name);
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.remove(key);
				if( value == null ) return null;
				
				if( value.b.a != null ) value.b.a.trigger(Tuple.of(key, null));
				return value.a;
			}
		}
		
		public void watch(String type, String name, BiConsumer<String, Data> callback)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(name);
			
			String key = implodeName(type, name);
			Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = null;
			synchronized(store)
			{
				value = store.get(key);
				if( value == null )
				{
					value = new Tuple<>(null, new Tuple<>(new Callback<Tuple<String, Data>>(), new Parameter(name.toLowerCase(Locale.ROOT).replace('_', '.'))));
					store.put(key, value);
				}
				else if( value.b.a == null ) value.b.a = new Callback<Tuple<String, Data>>();
			}
			value.b.a.then((v) -> callback.accept(v.a, v.b));
			value.b.a.trigger(Tuple.of(key, value.a));
		}
		
		public Map<String, Data> all(String type)
		{
			Map<String, Data> values = new HashMap<>();
			
			type = StringUtils.toLowerCase(type).replace('_', '.');
			
			synchronized(store)
			{
				String prefix = type + ":";
				for( Map.Entry<String, Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>>> entry : store.entrySet() )
				{
					if( entry.getKey().startsWith(prefix) )
						values.put(entry.getValue().b.b.name(), entry.getValue().a);
				}
				return values;
			}
		}
	}
	
	protected Class<? extends DefaultConfig.Implementation> defaultTarget() { return DefaultConfig.Implementation.class; }
	protected Supplier<? extends DefaultConfig.Implementation> defaultCreator() { return DefaultConfig.Implementation::new; }

	@Override
	public Template<? extends Config> template()
	{
		return super.template()
			.summary("In memory configuration")
			.description("Stores all the configuration parameters directly in memory. Environment variables and system properties are imported by default. "
				+ "They will be split by '_' or '.' and converted to lower case: Entity_Type_NAME will be converted to the configuration parameter "
				+ "entity type > name.")
			.builder((data, instance) -> {
				for( Map.Entry<String, String> entry : System.getenv().entrySet() )
				{
					String key = entry.getKey().replaceAll("[^a-zA-Z0-9_.-]", "");
					if( !key.isBlank() )
						instance.set(key, Data.of(entry.getValue()));
				}
				
				for( Map.Entry<Object, Object> entry : System.getProperties().entrySet() )
				{
					String key = entry.getKey().toString().replaceAll("[^a-zA-Z0-9_.-]", "");
					if( !key.isBlank() )
						instance.set(key, Data.of(entry.getValue().toString()));
				}
				
				Registry.add(instance);
			});
	}
}
