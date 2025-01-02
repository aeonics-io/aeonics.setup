package aeonics.manager.impl;

import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Message;
import aeonics.entity.Step;
import aeonics.entity.Step.Origin;
import aeonics.entity.security.User;
import aeonics.manager.Config;
import aeonics.manager.Executor;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.template.Channel;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Snapshotable.SnapshotMode;

public class DefaultLogger extends Manager<Logger>
{
	private static final class _Logger extends Origin.Type
	{
		@Override
		public void produce(Message message, String channel)
		{
			if( message == null ) return;
			if( !started() ) start();
			super.produce(message, channel);
		}
	}
	
	public static void register()
	{
		// calling this method will force initialization of all private static members
	}
	
	private static final _Logger origin = new Origin() { }
		.target(_Logger.class)
		.creator(_Logger::new)
		.template()
		.<Step.Template>cast()
		.output(new Channel("data").summary("Logs").description("Log entries"))
		.icon("description")
		.summary("Logger")
		.description("This data origin is the source for all log entries that are handled by the logger manager.")
		.create(Data.map().put("id", "10000000-1500000000000000"))
		.name("Logger")
		.internal(true)
		.snapshotMode(SnapshotMode.UPDATE)
		.cast();
		
	public static Origin.Type origin() { return origin; }

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
				Message msg = new Message(level + "/" + type)
					.user(User.SYSTEM.id())
					.content(Data.map()
						.put("date", System.currentTimeMillis())
						.put("level", level)
						.put("type", type)
						.put("message", bindMessage(message, params)));
				origin.produce(msg, "data");
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
			.description("Emit all logs as an origin step so that it can be managed like a data stream.")
			.config(Logger.class, new Parameter("level")
				.summary("The log level")
				.description("The log level is a number between 0 (log everything) and 1000 (log only critical errors). Only the logs with a level above the"
					+ "defined value will actually be logged, others will be ignored.")
				.rule(Parameter.Rule.INTEGER)
				.format(Parameter.Format.NUMBER)
				.optional(true)
				.defaultValue(700));
	}
}
