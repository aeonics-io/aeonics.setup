package aeonics.manager.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import aeonics.data.Data;
import aeonics.manager.Config;
import aeonics.manager.Manager;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Callback;
import aeonics.util.StringUtils;
import aeonics.util.Tuple;

public class DefaultConfig extends Manager<Config>
{
	private static class Implementation extends Config
	{
		private Map<String, Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>>> store = new HashMap<>(); 
		
		public void declare(String type, Parameter parameter)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(parameter);
			
			type = StringUtils.toLowerCase(type);
			
			synchronized(store)
			{
				String key = type + ":" + parameter.name();
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.get(key);
				if( value == null ) store.put(key, value = new Tuple<>(parameter.defaultValue(), new Tuple<>(null, parameter)));
				else value.b.b = parameter;
			}
		}

		public Data get(String type, String name)
		{
			if( type == null || type.isBlank() || name == null || name.isBlank() ) return Data.empty();
			
			type = StringUtils.toLowerCase(type);
			name = StringUtils.toLowerCase(name);
			
			String key = type + ":" + name;
			Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.get(key);
			if( value == null ) return null;
			return value.b.b.resolve(value.a, null);
		}
		
		public boolean contains(String type, String name)
		{
			if( type == null || type.isBlank() || name == null || name.isBlank() ) return false;
			
			type = StringUtils.toLowerCase(type);
			name = StringUtils.toLowerCase(name);
			
			String key = type + ":" + name;
			return store.containsKey(key);
		}

		public Data set(String type, String name, Data value)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(name);
			
			type = StringUtils.toLowerCase(type);
			name = StringUtils.toLowerCase(name);
			
			synchronized(store)
			{
				String key = type + ":" + name;
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> v = store.get(key);
				if( v == null ) store.put(key, v = new Tuple<>(null, new Tuple<>(null, new Parameter(name))));
				if( !v.b.b.validate(value) ) throw new IllegalArgumentException("Invalid value for parameter " + type + ":" + name);
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
			
			type = StringUtils.toLowerCase(type);
			name = StringUtils.toLowerCase(name);
			
			synchronized(store)
			{
				String key = type + ":" + name;
				Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = store.remove(key);
				if( value == null ) return null;
				
				if( value.b.a != null ) value.b.a.trigger(Tuple.of(key, null));
				return value.a;
			}
		}
		
		public void watch(String type, String name, Consumer<Tuple<String, Data>> callback)
		{
			Objects.requireNonNull(type);
			Objects.requireNonNull(name);
			
			type = StringUtils.toLowerCase(type);
			name = StringUtils.toLowerCase(name);
			
			String key = type + ":" + name;
			Tuple<Data, Tuple<Callback<Tuple<String, Data>>, Parameter>> value = null;
			synchronized(store)
			{
				value = store.get(key);
				if( value == null ) store.put(key, value = new Tuple<>(null, new Tuple<>(new Callback<Tuple<String, Data>>(), new Parameter(name))));
				else if( value.b.a == null ) value.b.a = new Callback<Tuple<String, Data>>();
			}
			value.b.a.then(callback);
			value.b.a.trigger(Tuple.of(key, value.a));
		}
		
		public Map<String, Data> all(String type)
		{
			Map<String, Data> values = new HashMap<>();
			
			type = StringUtils.toLowerCase(type);
			
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
	
	private static Template<Implementation> template = new Template<Implementation>(Implementation.class, StringUtils.toLowerCase(Config.class), StringUtils.toLowerCase(Manager.class))
	.creator(Implementation::new)
	.summary("In memory configuration")
	.description("Stores all the configuration parameters directly in memory. Environment variables and system properties are imported by default. "
		+ "They will be split by '_' or '.' and converted to lower case: Entity_Type_NAME will be converted to the configuration parameter "
		+ "entity type > name.")
	.builder((data, instance) -> {
		for( Map.Entry<String, String> entry : System.getenv().entrySet() )
			instance.set(entry.getKey(), Data.of(entry.getValue()));
		
		for( Map.Entry<Object, Object> entry : System.getProperties().entrySet() )
			instance.set(entry.getKey().toString(), Data.of(entry.getValue().toString()));
	});
	
	public Template<? extends Config> template() { return template; }
	public Class<? extends Config> entity() { return Implementation.class; }
}
