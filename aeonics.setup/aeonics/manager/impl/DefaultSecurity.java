package aeonics.manager.impl;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.entity.security.Policy;
import aeonics.entity.security.Provider;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.template.Template;
import aeonics.util.StringUtils;

public class DefaultSecurity extends Manager<Security>
{
	private static class Implementation extends Security
	{
		// =========================================
		//
		// CRYPTO HASH / ENCRYPT / DECRYPT
		//
		// =========================================
		
		private final int keySize = 32;
		
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
		// USER / ALLOWED / DENIED
		//
		// =========================================
		
		public List<Provider.Type> providers(String user)
		{
			List<Provider.Type> providers = new ArrayList<>();
			for( Provider.Type p : Registry.of(Provider.class) )
			{
				try
				{
					if( p.active() && p.supports(user) ) providers.add(p);
				}
				catch(Throwable t)
				{
					Manager.of(Logger.class).warning(Security.class, t);
				}
			}
			return providers;
		}
		
		public User.Type authenticate(Provider.Type provider, Data context)
		{
			if( provider == null || !provider.active() ) return User.ANONYMOUS;
			
			try
			{
				User.Type user = provider.authenticate(context);
				if( user == null ) return User.ANONYMOUS;
				return user;
			}
			catch(Throwable t)
			{
				Manager.of(Logger.class).warning(Security.class, t);
			}
			return User.ANONYMOUS;
		}
		
		public boolean granted(User.Type user, String scope, Data context)
		{
			if( user == null ) return false;
			if( user == User.SYSTEM ) return true;
			
			return !isExplicitlyDenied(user, scope, context) && isExplicitlyAllowed(user, scope, context);
		}
		
		public boolean isExplicitlyDenied(User.Type user, String scope, Data context)
		{
			if( user == null || scope == null ) return false;
			if( user == User.SYSTEM ) return false;
			
			try
			{
				for( Policy.Type policy : Registry.of(Policy.class) )
				{
					if( !policy.valueOf("scope").equals(scope) ) continue;
					if( policy.isDenied(user, context) )
						return true;
				}
				return false;
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
			return false;
		}
		
		public boolean isExplicitlyAllowed(User.Type user, String scope, Data context)
		{
			if( user == null || scope == null ) return false;
			if( user == User.SYSTEM ) return true;
			
			try
			{
				for( Policy.Type policy : Registry.of(Policy.class) )
				{
					if( !policy.valueOf("scope").equals(scope) ) continue;
					if( policy.isAllowed(user, context) )
						return true;
				}
				return false;
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
			return false;
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
				tokens.put(t.value(), t);
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
			if( token == null ) return null;
			
			try
			{
				Token t = tokens.get(token);
				if( t == null ) return null;
				if( !t.isValid() ) { tokens.remove(token); return null; }
				if( reset ) t.reset();
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
				tokens.remove(token.value());
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
				
				Iterator<Token> i = tokens.values().iterator();
				while( i.hasNext() )
				{
					Token t = i.next();
					if( t != null && (t.isFor(id) || !t.isValid()) ) i.remove();
				}
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
			}
		}
	}
	
	private static Template<Implementation> template = new Template<Implementation>(Implementation.class, StringUtils.toLowerCase(Security.class), StringUtils.toLowerCase(Manager.class))
	.creator(Implementation::new)
	.summary("Security manager")
	.description("Security layer that manages tokens locally. "
			+ "Hash functions are variable-iteration SHA-256. "
			+ "Encryption is performed using AES/GCM/NoPadding with an enforced key size of 265 bits.");
	
	public Template<? extends Security> template() { return template; }
	public Class<? extends Security> entity() { return Implementation.class; }
}
