package aeonics.entity.basic;

import java.util.function.Supplier;

import aeonics.entity.Destination;
import aeonics.entity.Message;
import aeonics.template.Channel;
import aeonics.template.Parameter;

public class Console extends Destination
{
	private static class Type extends Destination.Type
	{
		@Override
		public void accept(Message message, String input)
		{
			String output = valueOf("format", message.export()).asString();
			System.out.println(output);
		}
	}
	
	@Override
	protected Class<? extends Console.Type> defaultTarget() { return Console.Type.class; }
	@Override
	protected Supplier<? extends Console.Type> defaultCreator() { return Console.Type::new; }
	
	@Override
	public Destination.Template template()
	{
		return super.template()
			.summary("Console output")
			.description("Prints input messages content to the system console according to the provided format")
			.add(new Parameter("format")
				.summary("Format")
				.description("The format that should be used. It may contain bindings")
				.format(Parameter.Format.TEXT)
				.bindable(true)
				.optional(true)
				.defaultValue("${context:content}"))
			.<Destination.Template>cast()
			.input(new Channel("data")
				.summary("Data")
				.description("Any data"));
	}
}
