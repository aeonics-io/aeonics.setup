package aeonics.manager.impl;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.manager.Config;
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
			System.out.println(toJson(level, type, message, params));
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
			.summary("Console logger")
			.description("Sends all logs in JSON format to the standard output console.")
			.config(Logger.class, new Parameter("level")
				.summary("The log level")
				.description("The log level is a number between 0 (log everything) and 1000 (log only critical errors). Only the logs with a level above the"
					+ "defined value will actually be logged, others will be ignored.")
				.rule(Parameter.Rule.DIGIT)
				.format(Parameter.Format.NUMBER)
				.optional(true)
				.defaultValue(Data.of(700)));
	}
}
