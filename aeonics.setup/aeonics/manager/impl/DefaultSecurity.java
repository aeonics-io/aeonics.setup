package aeonics.manager.impl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.manager.Config;
import aeonics.manager.Lifecycle;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.manager.Timeout;
import aeonics.manager.Timeout.Tracker;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Callback;

public class DefaultSecurity extends Manager<Security>
{
	private static class Implementation extends Security
	{
		// =========================================
		//
		// CRYPTO HASH / ENCRYPT / DECRYPT
		//
		// =========================================
		
		private static final int keySize = 32;
		
		/**
		 * Make sure the key meets the keySize requirement of 256 bits (32 bytes)
		 */
		private byte[] normalizeKey(byte[] key)
		{
			if( key.length == keySize ) return key;
			else if( key.length < keySize )
			{
				byte[] bigger = new byte[keySize];
				for( int i = 0; i < bigger.length; i++ )
					bigger[i] = key[i % key.length];
				return bigger;
			}
			else
			{
				byte[] smaller = new byte[keySize];
				System.arraycopy(key, 0, smaller, 0, keySize);
				for( int i = keySize; i < key.length; i++ )
					smaller[i % keySize] ^= key[i];
				return smaller;
			}
		}
		
		public String encrypt(byte[] value, byte[] key)
		{
			try
			{
				Objects.requireNonNull(value);
				Objects.requireNonNull(key);
				if( key.length == 0 ) throw new IllegalArgumentException("Invalid empty key");
			
				byte[] nkey = normalizeKey(key);
				
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(nkey, "AES"));
				byte[] iv = cipher.getIV();
				
				if( iv.length > 0xFF ) throw new IllegalStateException("IV length exceeds single byte limit");
				
				ByteArrayOutputStream tmp = new ByteArrayOutputStream();
				tmp.write(iv.length);
				tmp.write(iv);
				tmp.write(cipher.doFinal(value));
					
				return Base64.getEncoder().encodeToString(tmp.toByteArray());
			}
			catch(Exception e)
			{
				throw new SecurityException("Encrypt failed");
			}
		}
		
		public byte[] decrypt(String value, byte[] key)
		{
			try
			{
				byte[] decoded = Base64.getDecoder().decode(value);
				byte[] nkey = normalizeKey(key);
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				/* AES has a block-size of 128 bits in all its variants.
				 * The number in AES-128/192/256 is the key-size.
				 * Since the block-size is 128 bits, GCM works exactly the same way for AES-256 as it does for AES-128.
				 * So we hardcode the "tLen" parameter to 128 bits 
				 *     InvalidAlgorithmParameterException: Unsupported TLen value; must be one of {128, 120, 112, 104, 96}
				 */
				GCMParameterSpec params = new GCMParameterSpec(128, Arrays.copyOfRange(decoded, 1, decoded[0]+1));
				cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(nkey, "AES"), params);
				
				return cipher.doFinal(Arrays.copyOfRange(decoded, decoded[0]+1, decoded.length));
			}
			catch(Exception e)
			{
				throw new SecurityException("Decrypt failed");
			}
		}
		
		public String hash(InputStream value)
		{
			try
			{
				MessageDigest md = MessageDigest.getInstance("SHA-256");
				try( DigestInputStream dis = new DigestInputStream(value, md) )
				{
					byte[] buffer = new byte[8192];
					while( dis.read(buffer) != -1 ); // process all data
				}
				
				// first pass
				byte[] hash = md.digest();
				
				int rounds = 10_000 + new BigInteger(hash).mod(BigInteger.valueOf(10_000)).intValue();
				for( ; rounds > 0; rounds-- ) hash = md.digest(hash);
				return parseBinaryHex(hash);
			}
			catch(Exception e)
			{
				throw new RuntimeException("Hash failed");
			}
		}
		
		public String hash(byte[] value, byte[] salt)
		{
			try
			{
				MessageDigest md = MessageDigest.getInstance("SHA-256");
				
				if( salt != null ) md.update(salt);
				
				// first pass
				byte[] hash = md.digest(value);
				
				int rounds = 10_000 + new BigInteger(hash).mod(BigInteger.valueOf(10_000)).intValue();
				for( ; rounds > 0; rounds-- ) hash = md.digest(hash);
				return parseBinaryHex(hash);
			}
			catch(Exception e)
			{
				throw new RuntimeException("Hash failed");
			}
		}
		
		public String randomHash()
		{
			try
			{
				byte[] hash = MessageDigest.getInstance("SHA-256").digest((System.nanoTime() + "/" + SecureRandom.getInstanceStrong().nextLong()).getBytes());
				return parseBinaryHex(hash);
			}
			catch(Exception e)
			{
				throw new RuntimeException("Hash failed");
			}
		}
		
		private byte[] hexArray = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
		private String parseBinaryHex(byte[] bin)
		{
			byte[] hex = new byte[bin.length*2];
			for( int i = 0, h = 0; i < bin.length; i++ )
			{
				h = bin[i] & 0xff;
				hex[i*2] = hexArray[h >>> 4];
				hex[i*2+1] = hexArray[h & 0x0F];
			}
			return new String(hex);
		}
		
		// =========================================
		//
		// TOKEN RELATED
		//
		// =========================================
		
		private Map<String, Token> tokens = new ConcurrentHashMap<>();
		
		public Token generateToken(User.Type user, long validity, boolean exclusive, String... scopes)
		{
			if( user == null || user == User.ANONYMOUS || user == User.SYSTEM || scopes == null || scopes.length == 0 ) 
				throw new IllegalArgumentException();
			
			try
			{
				if( exclusive ) clearTokens(user);
				Token t = new Token(user, validity, scopes);
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				if( storage == null )
					tokens.put(t.value(), t);
				else
					storage.put("token/" + t.value(), t.export());
				return t;
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
			return null;
		}
		
		public Token authenticate(String token, boolean reset)
		{
			if( token == null || token.isBlank() ) return null;
			
			try
			{
				Token t = null;
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				if( storage == null )
					t = tokens.get(token);
				else if( storage.containsEntry("token/" + token) )
					t = new Token(storage.getData("token/" + token));

				if( t == null ) return null;
				if( !t.isValid() )
				{
					if( storage == null ) tokens.remove(token);
					else storage.remove("token/" + token);
					return null; 
				}
				if( reset )
				{
					t.reset();
					if( storage != null ) storage.put("token/" + token, t.export());
				}
				return t;
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
			return null;
		}
		
		public void revokeToken(Token token)
		{
			if( token == null ) return;
			
			try
			{
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				if( storage == null )
					tokens.remove(token.value());
				else
					storage.remove("token/" + token.value());
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
		}
		
		public void clearTokens(User.Type user)
		{
			if( user == null || user == User.ANONYMOUS || user == User.SYSTEM ) return;
			
			try
			{
				String id = user.id();
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				
				if( storage == null ) 
					tokens.values().removeIf((t) -> { return t.isFor(id) || !t.isValid(); });
				else
				{
					for( String token : storage.list("token/") )
					{
						Data m = storage.getData(token);
						if( m == null || m.isEmpty() ) continue;
						Token t = new Token(m);
						if( t.isFor(id) || !t.isValid() ) storage.remove(token);
					}
				}
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
		}
		
		private Tracker<Void> tracker = new Tracker<Void>(null) 
		{
			private long next = 0;
			public long delay()
			{
				if( next == 0 || next < System.currentTimeMillis() ) checkNow();
				return next - System.currentTimeMillis();
			}
			
			private void checkNow()
			{
				final Long now = System.currentTimeMillis();
				AtomicLong min = new AtomicLong(300_000);
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				
				if( storage == null )
				{
					tokens.values().removeIf((t) ->
					{
						if( t == null || !t.isValid() ) return true;
						min.set(Math.min(min.get(), t.notAfter() - now));
						return false;
					});
				}
				else
				{
					for( String token : storage.list("token/") )
					{
						Data m = storage.getData(token);
						if( m == null || m.isEmpty() ) continue;
						Token t = new Token(m);
						if( !t.isValid() ) storage.remove(token);
						else min.set(Math.min(min.get(), t.notAfter() - now));
					}
				}
				
				if( min.get() <= 0 ) min.set(300_000);
				next = now + min.get();
			}
		};
	}
	
	protected Class<? extends DefaultSecurity.Implementation> defaultTarget() { return DefaultSecurity.Implementation.class; }
	protected Supplier<? extends DefaultSecurity.Implementation> defaultCreator() { return DefaultSecurity.Implementation::new; }

	@Override
	public Template<? extends Security> template()
	{
		return super.template()
			.summary("Security manager")
			.description("Security layer that manages tokens locally. "
				+ "Hash functions are variable-iteration SHA-256. "
				+ "Encryption is performed using AES/GCM/NoPadding with an enforced key size of 265 bits.")
			.config(Security.class, new Parameter("token.storage")
				.summary("Token storage")
				.description("The name or id of the storage for access tokens. If the storage does not exist, a local temporary (ouf-of-storage) location is used instead.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue(Data.empty()))
			.onCreate((data, instance) ->
			{
				if( Manager.of(Lifecycle.class).phase() == Lifecycle.Phase.RUN ) 
					Manager.of(Timeout.class).watch(((Implementation)instance).tracker);
				else
					Lifecycle.before(Lifecycle.Phase.RUN, Callback.once(() -> { Manager.of(Timeout.class).watch(((Implementation)instance).tracker); }));
			});
	}
}
