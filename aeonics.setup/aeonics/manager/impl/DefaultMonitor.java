package aeonics.manager.impl;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.manager.Config;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Monitor;
import aeonics.template.Parameter;
import aeonics.template.Template;

public class DefaultMonitor extends Manager<Monitor>
{
	private static class Implementation extends Monitor
	{
		private static final String FROM = "_from";
		private static final String TO = "_to";
		private static final String COUNT = "_count";
		private static final String TOTAL = "_total";
		
		private boolean enabled = false;
		private long window = 60_000;
		private volatile long from = 0;
		private volatile long to = 0;
		
		private volatile boolean clean = false;
		private volatile Data past = null;
		private volatile Data current = null;
		
		public void add(String level1, String level2, String level3, String level4, long value)
		{
			if( !enabled ) return;
			
			Objects.requireNonNull(level1);
			Objects.requireNonNull(level2);
			Objects.requireNonNull(level3);
			Objects.requireNonNull(level4);
			
			Data main = getOrReset();
			Data lvl1 = getOrCreate(main, level1, false);
			Data lvl2 = getOrCreate(lvl1, level2, false);
			Data lvl3 = getOrCreate(lvl2, level3, false);
			Data lvl4 = getOrCreate(lvl3, level4, true);
			
			((LongAdder)lvl4.get(COUNT).get()).increment();
			
			if( value != 0 )
			{
				((LongAdder)lvl4.get(TOTAL).get()).add(value);
			}
		}
		
		private Data getOrCreate(Data data, String key, boolean includeCountAndTotal)
		{
			Data value = data.get(key);
			if( !value.isMap() )
			{
				synchronized(data)
				{
					value = data.get(key);
					if( !value.isMap() )
					{
						value = Data.map();
						if( includeCountAndTotal ) value.put(COUNT, new LongAdder()).put(TOTAL, new LongAdder());
						data.put(key, value);
					}
				}
			}
			return value;
		}

		public Data report(String level1, String level2, String level3, String level4)
		{
			getOrReset();
			
			Data main = past;
			if( !clean )
			{
				if( main == null ) return Data.map();
				
				synchronized(this)
				{
					if( !clean )
					{
						cleanData(main);
						clean = true;
					}
				}
			}
			
			if( level1 == null && level2 == null && level3 == null && level4 == null)
				return main;
			
			Data filtered = Data.map();
			for( Map.Entry<String, Data> l1 : main.entrySet() )
			{
				if( l1.getKey().equals(FROM) || l1.getKey().equals(TO) )
				{
					filtered.put(l1.getKey(), l1.getValue());
					continue;
				}
				
				if( level1 == null || level1.equals(l1.getKey()) && l1.getValue().isMap() )
				{
					Data _l2 = Data.map();
					for( Map.Entry<String, Data> l2 : l1.getValue().entrySet() )
					{
						if( level2 == null || level2.equals(l2.getKey()) && l2.getValue().isMap() )
						{
							Data _l3 = Data.map();
							for( Map.Entry<String, Data> l3 : l2.getValue().entrySet() )
							{
								if( level3 == null || level3.equals(l3.getKey()) && l3.getValue().isMap() )
								{
									Data _l4 = Data.map();
									for( Map.Entry<String, Data> l4 : l3.getValue().entrySet() )
									{
										if( l4.getKey().equals(COUNT) || l4.getKey().equals(TOTAL) )
											_l4.put(l4.getKey(), l4.getValue());
									}
									_l3.put(l3.getKey(), _l4);
								}
							}
							_l2.put(l2.getKey(), _l3);
						}
					}
					filtered.put(l1.getKey(), _l2);
				}
			}
			return filtered;
		}
		
		private void cleanData(Data data)
		{
			for( Map.Entry<String, Data> sub : data.entrySet() )
			{
				if( sub.getValue().isMap() ) cleanData(sub.getValue());
				else if( sub.getValue().is(LongAdder.class) )
					sub.setValue(Data.of(((LongAdder)sub.getValue().get()).longValue()));
			}
		}
		
		private Data getOrReset()
		{
			long now = System.currentTimeMillis();
			if( to < now || current == null )
			{
				synchronized(this)
				{
					if( to < now || current == null  )
					{
						from = now - (now % window);
						to = from + window;
						
						past = current;
						current = Data.map().put(FROM, from).put(TO, to);
						clean = false;
					}
				}
			}
			
			return current;
		}
		
		public void config(String key, Data value)
		{
			if( Config.implodeName(Monitor.class, "window").equals(key) )
			{
				try { window = value.asInt(); }
				catch(Exception e) { Manager.of(Logger.class).severe(Monitor.class, "Could not set monitor window to {}. Current value {} is unchanged.", value, window); }
			}
			else if( Config.implodeName(Monitor.class, "enabled").equals(key) )
			{
				try { enabled = value.asBool(); }
				catch(Exception e) { Manager.of(Logger.class).severe(Monitor.class, "Could not set monitor enabled state to {}. Current value {} is unchanged.", value, enabled); }
			}
		}
	}
	
	protected Class<? extends DefaultMonitor.Implementation> defaultTarget() { return DefaultMonitor.Implementation.class; }
	protected Supplier<? extends DefaultMonitor.Implementation> defaultCreator() { return DefaultMonitor.Implementation::new; }
	
	public Template<? extends Monitor> template()
	{
		return super.template()
			.summary("Windowed monitor")
			.description("This monitor will keep track of the counters and accumulated values for a specified amount of time. "
				+ "Reported values are always the last completed window, not the current one, "
				+ "and include a \"" + Implementation.COUNT + "\" property for the number of occurences and a \"" + Implementation.TOTAL + "\" property for the accumulated value.")
			.config(Monitor.class, new Parameter("window")
				.summary("Time window")
				.description("The amount of time in milliseconds to keep track of metrics and then reset to 0. If this value is modified, the window will be applied after the end of the current window.")
				.rule(Parameter.Rule.DIGIT)
				.format(Parameter.Format.NUMBER)
				.optional(true)
				.defaultValue(60_000))
			.config(Monitor.class, new Parameter("enabled")
				.summary("Enable monitoring")
				.description("Whether or not the monitoring should be enabled. If set to false, then all monitoring requests are ignored.")
				.rule(Parameter.Rule.BOOLEAN)
				.format(Parameter.Format.BOOLEAN)
				.optional(true)
				.defaultValue(false))
				;
	}
}
