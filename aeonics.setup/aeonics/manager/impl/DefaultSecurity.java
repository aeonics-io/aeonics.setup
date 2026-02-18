package aeonics.manager.impl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
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
import aeonics.util.Tuples.Tuple;

public class DefaultSecurity extends Manager<Security>
{
	private static class Implementation extends Security
	{
		private static SecureRandom random;
		static { try { random = SecureRandom.getInstanceStrong(); } catch(Exception e) { random = null; /* ignore: there is nothing we can do about it */ } }

		private byte[] pepper = null;

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
		
		public String encrypt(byte[] value, PublicKey key)
		{
			try
			{
				Objects.requireNonNull(value);
				Objects.requireNonNull(key);
				
				KeyGenerator keygen = KeyGenerator.getInstance("AES");
				keygen.init(256, random);
	            SecretKey symmetricKey = keygen.generateKey();
				
	            byte[] iv = new byte[12];
	            random.nextBytes(iv);
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.ENCRYPT_MODE, symmetricKey, new GCMParameterSpec(128, iv), random);
				
				Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
		        rsa.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec(
		        	"SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT), random);
		        byte[] encKey = rsa.doFinal(symmetricKey.getEncoded());
		        if( encKey.length > 0xFFFF ) throw new IllegalArgumentException("Key length overflow");
		        
				ByteArrayOutputStream tmp = new ByteArrayOutputStream();
				tmp.write((encKey.length >>> 8) & 0xFF);
				tmp.write(encKey.length & 0xFF);
				tmp.write(encKey);
				tmp.write((iv.length >> 8) & 0xFF);
				tmp.write(iv.length & 0xFF);
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
				Objects.requireNonNull(value);
		        Objects.requireNonNull(key);
		        
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
		
		public byte[] decrypt(String value, PrivateKey key)
		{
			try
			{
				Objects.requireNonNull(value);
		        Objects.requireNonNull(key);
		        
				byte[] decoded = Base64.getDecoder().decode(value);
				
				int encKeyLength = ((decoded[0] & 0xFF) << 8) | (decoded[1] & 0xFF);
				byte[] encKey = Arrays.copyOfRange(decoded, 2, encKeyLength + 2);
				int ivLength = ((decoded[encKeyLength + 2] & 0xFF) << 8) | (decoded[encKeyLength + 3] & 0xFF);
				byte[] iv = Arrays.copyOfRange(decoded, encKeyLength + 4, encKeyLength + ivLength + 4);
				
				Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
		        rsa.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec(
		        	"SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT), random);
		        byte[] symmetricKey = rsa.doFinal(encKey);
		        
		        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symmetricKey, "AES"), new GCMParameterSpec(128, iv), random);
				
		        int payloadStart = encKeyLength + ivLength + 4;
		        byte[] payload = Arrays.copyOfRange(decoded, payloadStart, decoded.length);
				return cipher.doFinal(payload);
			}
			catch(Exception e)
			{
				throw new SecurityException("Decrypt failed");
			}
		}
		
		public boolean verify(String signature, byte[] value, PublicKey key)
		{
			try
			{
				Signature sig = Signature.getInstance("SHA256withRSA");
				sig.initVerify(key);
				sig.update(value);
				return sig.verify(Base64.getDecoder().decode(signature));
			}
			catch(Exception e) { return false; }
		}
		
		public String sign(byte[] value, PrivateKey key)
		{
			try
			{
				Signature sig = Signature.getInstance("SHA256withRSA");
				sig.initSign(key);
				sig.update(value);
				return Base64.getEncoder().encodeToString(sig.sign());
			}
			catch(Exception e)
			{
				throw new SecurityException("Signature failed");
			}
		}
		
		public String hash(byte[] value, byte[] salt, byte[] pepperOverride)
		{
			try
			{
				int rounds = Manager.of(Config.class).get(Security.class, "hash.rounds").asInt();
				if( rounds < 10_000 ) rounds = 100_000;

				// convert byte[] to char[] using ISO-8859-1 (1:1 byte-to-char mapping)
				char[] password = new char[value.length];
				for( int i = 0; i < value.length; i++ ) password[i] = (char)(value[i] & 0xFF);

				PBEKeySpec spec = new PBEKeySpec(password, salt != null ? salt : new byte[0], rounds, 256);
				Arrays.fill(password, '\0');

				SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
				byte[] hash = skf.generateSecret(spec).getEncoded();
				spec.clearPassword();

				// apply pepper via HMAC: explicit pepper takes priority, then instance pepper
				byte[] effectivePepper = pepperOverride != null ? pepperOverride : pepper;
				if( effectivePepper != null )
				{
					Mac mac = Mac.getInstance("HmacSHA256");
					mac.init(new SecretKeySpec(effectivePepper, "HmacSHA256"));
					byte[] peppered = mac.doFinal(hash);
					Arrays.fill(hash, (byte)0);
					hash = peppered;
				}
				
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
				byte[] hash = MessageDigest.getInstance("SHA-256").digest((System.nanoTime() + "/" + random.nextLong()).getBytes());
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
		// AUTHENTICATION RATE LIMITING
		//
		// =========================================

		private final Map<String, Tuple<Integer, Long>> throttle = new ConcurrentHashMap<>();
		private final AtomicBoolean throttleTrackerActive = new AtomicBoolean(false);

		public boolean isLocked(String userId)
		{
			if( userId == null || userId.isBlank() ) return false;
			if( User.ANONYMOUS.id().equals(userId) || User.SYSTEM.id().equals(userId) ) return false;

			Tuple<Integer, Long> state = throttle.get(userId);
			if( state == null ) return false;

			return state.b > System.currentTimeMillis();
		}

		public void recordFailedAuthentication(String userId)
		{
			if( userId == null || userId.isBlank() ) return;
			if( User.ANONYMOUS.id().equals(userId) || User.SYSTEM.id().equals(userId) ) return;

			Manager.of(Logger.class).log(Logger.WARNING+1, Security.class, "Failed authentication attempt for " + userId);
			AtomicBoolean shouldWakeTracker = new AtomicBoolean(false);

			throttle.compute(userId, (key, current) ->
			{
				int attempts = (current == null) ? 1 : current.a + 1;
				long lockUntil = 0;

				if( attempts > 3 )
				{
					long lockDuration = Math.min((attempts - 3) * 60_000L, 300_000L);
					lockUntil = System.currentTimeMillis() + lockDuration;
					Manager.of(Logger.class).warning(Security.class, "Authentication throttled for user " + userId);
					shouldWakeTracker.set(true);
				}

				return Tuple.of(attempts, lockUntil);
			});

			if( shouldWakeTracker.get() && throttleTrackerActive.compareAndSet(false, true) )
				Manager.of(Timeout.class).refresh();
		}

		public void recordSuccessfulAuthentication(String userId)
		{
			if( userId == null || userId.isBlank() ) return;
			if( User.ANONYMOUS.id().equals(userId) || User.SYSTEM.id().equals(userId) ) return;
			
			Manager.of(Logger.class).log(Logger.FINE+1, Security.class, "Authentication success for " + userId);
			throttle.remove(userId);
		}

		// =========================================
		//
		// TOKEN RELATED
		//
		// =========================================

		private Map<String, Token> tokens = new ConcurrentHashMap<>();
		
		public synchronized Token generateToken(User.Type user, long validity, boolean exclusive, String... scopes)
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
		
		public synchronized Token authenticate(String token, boolean reset)
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
		
		public synchronized void revokeToken(Token token)
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
		
		public synchronized void clearTokens(User.Type user)
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
		
		public synchronized Collection<Token> listTokens(User.Type user)
		{
			if( user == null || user == User.ANONYMOUS || user == User.SYSTEM ) return Collections.emptyList();
			
			try
			{
				String id = user.id();
				Storage.Type storage = Registry.of(Storage.class).get(Manager.of(Config.class).get(Security.class, "token.storage").asString());
				
				if( storage == null )
				{
					return tokens.values().stream()
						.filter(t -> t.isFor(id)) // select tokens for that user
						.map(t -> new Token(t.export())) // create a copy
						.collect(Collectors.toList());
				}
				else
				{
					Collection<Token> tokens = new ArrayList<>();
					for( String token : storage.list("token/") )
					{
						Data m = storage.getData(token);
						if( m == null || m.isEmpty() ) continue;
						Token t = new Token(m);
						if( t.isFor(id) ) tokens.add(t);
					}
					return tokens;
				}
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).warning(Security.class, e);
				return Collections.emptyList();
			}
		}
		
		private Tracker<Void> tracker = new Tracker<Void>("Security Token Timeout Tracker") 
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

		private final Tracker<Void> throttleTracker = new Tracker<Void>("Security Auth Throttle Tracker")
		{
			public long delay()
			{
				if( throttle.isEmpty() )
				{
					throttleTrackerActive.set(false);
					return Long.MAX_VALUE / 2;
				}

				long now = System.currentTimeMillis();
				long shortest = Long.MAX_VALUE / 2;

				var it = throttle.entrySet().iterator();
				while( it.hasNext() )
				{
					var entry = it.next();
					Tuple<Integer, Long> state = entry.getValue();
					if( state.b <= now )
					{
						it.remove();
						continue;
					}
					long remaining = state.b - now;
					if( remaining < shortest ) shortest = remaining;
				}

				if( throttle.isEmpty() )
				{
					throttleTrackerActive.set(false);
					return Long.MAX_VALUE / 2;
				}
				return Math.max(1, shortest);
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
				+ "Hash functions use PBKDF2WithHmacSHA256 with optional HMAC pepper. "
				+ "Encryption is performed using AES/GCM/NoPadding with an enforced key size of 256 bits.")
			.config(Security.class, new Parameter("token.storage")
				.summary("Token storage")
				.description("The name or id of the storage for access tokens. If the storage does not exist, a local temporary (ouf-of-storage) location is used instead.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue(Data.empty()))
			.config(Security.class, new Parameter("hash.rounds")
				.summary("Hash iteration count")
				.description("The number of PBKDF2 iterations for password hashing. Higher values increase brute-force resistance but consume more CPU. Minimum: 10000 for constrained devices, recommended: 600000 for dedicated servers.")
				.format(Parameter.Format.NUMBER)
				.rule(Parameter.Rule.DIGIT)
				.optional(true)
				.defaultValue(100_000))
			.onCreate((config, instance) ->
			{
				// undocumented parameter on purpose
				// so that it does not get snapshotted and is not readdable or 
				// settable other than from here
				if( config.isMap("parameters") && !config.get("parameters").isEmpty("pepper") )
					((Implementation)instance).pepper = config.get("parameters").asString("pepper").getBytes(StandardCharsets.ISO_8859_1);
				else
					Manager.of(Logger.class).warning(Security.class, "No hash pepper configured. Set AEONICS_SECURITY_HASH_PEPPER for production use.");
				
				if( Manager.of(Lifecycle.class).phase() == Lifecycle.Phase.RUN )
				{
					Manager.of(Timeout.class).watch(((Implementation)instance).tracker);
					Manager.of(Timeout.class).watch(((Implementation)instance).throttleTracker);
				}
				else
				{
					Lifecycle.before(Lifecycle.Phase.RUN, Callback.once(() -> {
						Manager.of(Timeout.class).watch(((Implementation)instance).tracker);
						Manager.of(Timeout.class).watch(((Implementation)instance).throttleTracker);
					}));
				}
			});
	}
}
