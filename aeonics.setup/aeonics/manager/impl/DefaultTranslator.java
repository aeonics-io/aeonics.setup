package aeonics.manager.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.function.Supplier;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Translator;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Hardware;
import aeonics.util.Json;

public class DefaultTranslator extends Manager<Translator>
{
	private static class Implementation extends Translator
	{
		private String language = "en";
		public void language(String language)
		{
			if( language == null || language.length() != 2 ) language = "en";
			if( !language.matches("^[a-z][a-z]$") ) throw new IllegalArgumentException("Invalid language");
			
			this.language = language;
		}
		public String language() { return this.language; }
		
		private Data cache = Data.map();

		public String get(String key, String language)
		{
			if( key == null || key.isBlank() ) return "";
			if( language == null || language.length() != 2 ) language = language();
			if( !cache.containsKey(language) ) preload(language);
			
			return cache.get("language").asString(key);
		}

		public void set(String key, String text, String language)
		{
			if( key == null || key.isBlank() ) return;
			if( language == null || language.length() != 2 ) language = language();
			if( !cache.containsKey(language) ) preload(language);
			
			cache.get("language").put(key, text);
		}
		
		public void clear(String language)
		{
			if( language == null || language.length() != 2 ) language = language();
			synchronized(cache) { cache.remove(language); }
		}
		
		public void clear()
		{
			synchronized(cache) { cache.clear(); }
		}
		
		private void preload(String language)
		{
			if( language == null || language.length() != 2 ) language = language();
			if( !language.matches("^[a-z][a-z]$") ) throw new IllegalArgumentException("Invalid language");
			if( cache.containsKey(language) ) return;
			
			synchronized(cache)
			{
				if( cache.containsKey(language) ) return;
				
				Data lang = Data.map();
				try
				{
					Path dir = Paths.get(valueOf("folder").asString() + File.separatorChar + language);
					if( !dir.toFile().isDirectory() ) return;
					
					Files.walkFileTree(dir, new SimpleFileVisitor<Path>()
					{
						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
						{
							if( !attrs.isRegularFile() || !Files.isReadable(file) ) return FileVisitResult.CONTINUE;
							
							if( file.toString().endsWith(".json") )
							{
								try
								{
									Hardware.RAM.waitForSpace(attrs.size()*2, 1000);
									
									Data values = Json.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
									if( !values.isMap() ) throw new Exception("Invalid translation file " + file.toString());
									
									for( Map.Entry<String, Data> e : values.entrySet() ) lang.put(e.getKey(), e.getValue().asString());
								}
								catch(Exception x)
								{
									Manager.of(Logger.class).info(Translator.class, x);
								}
							}
							
							return FileVisitResult.CONTINUE;
						}
					});
				}
				catch(Exception e)
				{
					Manager.of(Logger.class).warning(Translator.class, e);
				}
				finally
				{
					cache.put(language, lang);
				}
			}
		}
	}
	
	protected Class<? extends DefaultTranslator.Implementation> defaultTarget() { return DefaultTranslator.Implementation.class; }
	protected Supplier<? extends DefaultTranslator.Implementation> defaultCreator() { return DefaultTranslator.Implementation::new; }

	@Override
	public Template<? extends Translator> template()
	{
		return super.template()
			.summary("Basic translator")
			.description("This translator implementation will load translations from JSON files in the target language folder. "
				+ "Translation files are lazily loaded at first use only (or after being cleared). "
				+ "Manually set entries will be available but will not be persisted.")
			.add(new Parameter("default")
				.summary("Default language")
				.description("The default language of the translator. It should be a ISO-639 (2 letter) language code.")
				.defaultValue(Data.of("en")))
			.add(new Parameter("folder")
				.summary("Resource folder")
				.description("The name of the folder from which translations can be loaded. That folder should contain one subfolder per language.")
				.defaultValue(Data.of("translations")))
			.builder((data, instance) ->
			{
				if( !data.isEmpty("default") ) instance.language(data.asString("default"));
				Registry.add(instance);
			})
			.modifier((data, instance) ->
			{
				if( !data.isEmpty("default") ) instance.language(data.asString("default"));
			});
	}
}
