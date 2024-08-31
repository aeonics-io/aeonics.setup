package aeonics.manager.impl;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.entity.Registry;
import aeonics.entity.Topic;
import aeonics.entity.security.User;
import aeonics.manager.Config;
import aeonics.manager.Executor;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Parameter;
import aeonics.template.Template;

public class DefaultLogger extends Manager<Logger>
{
	private static class Implementation extends Logger
	{
		public Implementation()
		{
			// copy the default log level
			this.level = Logger.CONSOLE.level();
		}
		
		public void handle(int level, String type, String message, Object... params)
		{
			if( level < level() || message == null || message.isBlank() ) return;
			
			Runnable publish = () ->
			{
				Topic.Type topic = Registry.of(Topic.class).get(Manager.of(Config.class).get(Logger.class, "topic").asString());
				if( topic != null )
				{
					topic.publish(
						new Message(level + "/" + type)
							.user(User.SYSTEM.id())
							.content(Data.map()
								.put("date", System.currentTimeMillis())
								.put("level", level)
								.put("type", type)
								.put("message", bindMessage(message, params)))
						);
					return;
				}
				
				// fallback
				Logger.CONSOLE.log(level, type, message, params);
			};
			
			if( Manager.of(Executor.class) != null && !Manager.of(Executor.class).isNormal() )
				Manager.of(Executor.class).normal(() -> publish.run());
			else
				publish.run();
		}
		
		public void config(String key, Data value)
		{
			if( Config.implodeName(Logger.class, "level").equals(key) )
			{
				try { level(value.asInt()); }
				catch(Exception e) { log(Logger.SEVERE, Logger.class, "Could not set log level to {}. Current value {} is unchanged.", value, level()); }
			}
		}
	}
	
	protected Class<? extends DefaultLogger.Implementation> defaultTarget() { return DefaultLogger.Implementation.class; }
	protected Supplier<? extends DefaultLogger.Implementation> defaultCreator() { return DefaultLogger.Implementation::new; }
	
	public Template<? extends Logger> template()
	{
		return super.template()
			.summary("Data logger")
			.description("Sends all logs in a topic so that it can be managed like a data stream. If the topic is not defined, then it falls back to the console logger.")
			.config(Logger.class, new Parameter("level")
				.summary("The log level")
				.description("The log level is a number between 0 (log everything) and 1000 (log only critical errors). Only the logs with a level above the"
					+ "defined value will actually be logged, others will be ignored.")
				.rule(Parameter.Rule.DIGIT)
				.format(Parameter.Format.NUMBER)
				.optional(true)
				.defaultValue(700))
			.config(Logger.class, new Parameter("topic")
				.summary("Logger topic")
				.description("The topic in which to publish log messages.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue("log"));
	}
}
