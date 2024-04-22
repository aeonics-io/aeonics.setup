package aeonics.manager.impl;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLHandshakeException;

import aeonics.data.Data;
import aeonics.manager.Config;
import aeonics.manager.Executor;
import aeonics.manager.Executor.Task;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Network;
import aeonics.manager.Timeout;
import aeonics.manager.Timeout.Tracker;
import aeonics.manager.Network.Connection;
import aeonics.manager.Network.SecurityOptions;
import aeonics.manager.Network.Server;
import aeonics.template.Parameter;
import aeonics.template.Template;
import aeonics.util.Callback;
import aeonics.util.Hardware;

public class DefaultNetwork extends Manager<Network>
{
	private static class Implementation extends Network implements Closeable
	{
		// =========================================
		//
		// OVERRIDES
		//
		// =========================================
		
		public Connection client(String remoteAddress, int remotePort, SecurityOptions options) throws IOException
		{
			while( !initialized.get() )
				LockSupport.parkNanos(50_000_000);
			
			SocketChannel channel = null;
			try
			{
				channel = SocketChannel.open();
				try { channel.setOption(StandardSocketOptions.SO_REUSEADDR, true); } catch(Exception e) { Manager.of(Logger.class).finest(Network.class, "Socket option SO_REUSEADDR could not be set for {}:{}", remoteAddress, remotePort); }
				channel.configureBlocking(false);
				
				ConnectionImplementation c = new ConnectionImplementation(channel, true);
				
				channel.register(selector, SelectionKey.OP_CONNECT, c);
				selector.wakeup();
				if( channel.connect(new InetSocketAddress(remoteAddress, remotePort)) )
					onConnectable2(c);
				return (options == null ? c : securize(c, options));
			}
			catch(IOException e)
			{
				channel.close();
				Manager.of(Logger.class).info(Network.class, e);
				throw e;
			}
		}

		public Server server(String localAddress, int localPort, SecurityOptions options) throws IOException
		{
			while( !initialized.get() )
				LockSupport.parkNanos(50_000_000);
			
			ServerSocketChannel channel = null;
			try
			{
				channel = ServerSocketChannel.open();
				try { channel.setOption(StandardSocketOptions.SO_REUSEADDR, true); } catch(Exception e) { Manager.of(Logger.class).finest(Network.class, "Socket option SO_REUSEADDR could not be set for {}:{}", localAddress, localPort); }
				try { channel.setOption(StandardSocketOptions.SO_REUSEPORT, true); } catch(Exception e) { Manager.of(Logger.class).finest(Network.class, "Socket option SO_REUSEPORT could not be set for {}:{}", localAddress, localPort); }
				channel.bind(new InetSocketAddress(localAddress, localPort), 50);
				channel.configureBlocking(false);
				
				ServerImplementation s = new ServerImplementation(channel, options);
				
				channel.register(selector, SelectionKey.OP_ACCEPT, s);
				selector.wakeup();
				Manager.of(Logger.class).config(Network.class, "Server listening on {}:{}", localAddress, localPort);
				return s;
			}
			catch(IOException e)
			{
				Manager.of(Logger.class).info(Network.class, e);
				channel.close();
				throw e;
			}
		}
		
		public Connection securize(Connection unsecure, SecurityOptions options)
		{
			if( options == null || unsecure.isSecure() ) return unsecure;			
			Connection s = new SecureConnectionImplementation(unsecure, options);
			return s;
		}
		
		public void refresh()
		{
			if( initialized.get() ) selector.wakeup();
		}
		
		public void close() { task.cancel(); }
		
		// =========================================
		//
		// HANDLERS FOR THE SELECTOR OPERATIONS
		//
		// =========================================
		
		private Selector selector;
		private int BUFFER_SIZE = 1024*64; // default TCP packet size
		private ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
		
		private void onAcceptable(SelectionKey key) throws Exception
		{
			if( !(key.attachment() instanceof ServerImplementation) ) throw new IllegalStateException("Invalid attachment on acceptable selection key");
			
			ServerImplementation s = (ServerImplementation) key.attachment();
			ServerSocketChannel channel = s.channel();
			
			do
			{
				SocketChannel client = channel.accept();
				if( client == null ) break;
				client.configureBlocking(false);
				
				ConnectionImplementation unsecure = new ConnectionImplementation(client, false);
				Connection secure = (s.isSecure() ? securize(unsecure, s.security()) : null);
				
				s.onAccept().trigger(secure == null ? unsecure : secure)
					.then(() -> { onConnectable2(unsecure); });
			}
			while( true );
		}
		
		private void onConnectable(SelectionKey key) throws Exception
		{
			if( !(key.attachment() instanceof ConnectionImplementation) ) throw new IllegalStateException("Invalid attachment on connectable selection key");
			
			key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
			ConnectionImplementation c = (ConnectionImplementation) key.attachment();
			
			onConnectable2(c);
		}
		
		private void onConnectable2(ConnectionImplementation c)
		{
			try
			{
				SocketChannel channel = c.channel();
				if( channel.isConnectionPending() ) channel.finishConnect();
				
				String localAddress = ((InetSocketAddress) channel.getLocalAddress()).getAddress().getHostAddress();
				int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
				String remoteAddress = ((InetSocketAddress) channel.getRemoteAddress()).getAddress().getHostAddress();
				int remotePort = ((InetSocketAddress) channel.getRemoteAddress()).getPort();
				
				try { channel.setOption(StandardSocketOptions.SO_LINGER, 1); } catch(Exception e) { Manager.of(Logger.class).finest(Network.class, "Socket option SO_LINGER could not be set for {}:{} - {}:{}", localAddress, localPort, remoteAddress, remotePort); }
				try { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true); } catch(Exception e) { Manager.of(Logger.class).finest(Network.class, "Socket option SO_KEEPALIVE could not be set for {}:{} - {}:{}", localAddress, localPort, remoteAddress, remotePort); }
				
				Manager.of(Logger.class).finer(Network.class, "Connection established between {}:{} and {}:{}", localAddress, localPort, remoteAddress, remotePort);
				c.connected.set(true);
				
				SelectionKey key = channel.register(selector, SelectionKey.OP_READ, c);
				selector.wakeup();
				onReadable2(c, key);
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).info(Network.class, e);
				try { c.close(); } catch(Exception ex) { };
			}
		}
		
		private void onReadable(SelectionKey key) throws Exception
		{
			if( !(key.attachment() instanceof ConnectionImplementation) ) throw new IllegalStateException("Invalid attachment on readable selection key");
			
			ConnectionImplementation c = (ConnectionImplementation) key.attachment();
			
			onReadable2(c, key);
		}
		
		private void onReadable2(ConnectionImplementation c, SelectionKey key)
		{
			SocketChannel channel = c.channel();
			if( channel == null || !channel.isConnected() ) return;
			
			// TODO : decide if we do async IO reads. This increases the overhead (latency) but allows more parallel reads (throughput)
			//key.interestOpsAnd(~SelectionKey.OP_READ);
			//Manager.of(Executor.class).io(() -> 
			//{
				boolean ready = false;
				boolean full = false;
				synchronized(buffer)
				{
					do
					{
						int readCount = 0;
						try
						{
							do { readCount = channel.read(buffer); } while( readCount > 0 );
						}
						catch(Exception ex)
						{
							Manager.of(Logger.class).finest(Network.class, ex);
							readCount = -1;
						}
						
						full = (buffer.remaining() == 0);
						if( buffer.position() > 0 )
						{
							buffer.flip();
							
							Hardware.RAM.waitForSpace(buffer.limit(), 0);
							
							byte[] data = new byte[buffer.limit()]; 
							buffer.get(data);
							c.fifo.offer(data);
							c.resetTimeout();
							buffer.clear();
							ready = true;
						}
						
						if( readCount == -1 )
						{
							try { c.close(); } catch(Exception ex) { };
						}
					}
					while(full);
				}
				
				if( ready )
					c.onReady().trigger(c);
			//}).then(() -> 
			//{
			//	key.interestOpsOr(SelectionKey.OP_READ);
			//	selector.wakeup();
			//});
		}
		
		private void onSelect(SelectionKey key)
		{
			try
			{
				if( key == null ) throw new IllegalStateException("Channel selection key is null");
				else if( !key.isValid() )
				{
					if( key.attachment() instanceof Closeable )
					{
						try { ((Closeable)key.attachment()).close(); } catch(Exception ex) { };
					}
					key.attach(null);
					key.cancel();
				}
				else if( key.isAcceptable() )
				{
					onAcceptable(key);
				}
				else if( key.isConnectable() )
				{
					onConnectable(key);
				}
				else if( key.isReadable() )
				{
					onReadable(key);
				}
				else throw new IllegalStateException("Invalid selection key operation");
			}
			catch(Throwable t)
			{
				if( key == null ) return;
				
				// client most likely disconnected
				Manager.of(Logger.class).finer(Network.class, t);
				if( key.attachment() instanceof Closeable )
				{
					try { ((Closeable)key.attachment()).close(); } catch(Exception ex) { };
				}
				key.attach(null);
				key.cancel();
			}
		};
		
		// =========================================
		//
		// BACKGROUND TASK
		//
		// =========================================
		
		private AtomicBoolean initialized = new AtomicBoolean(false);
		private Task<Void> task = Manager.of(Executor.class).background(() ->
		{
			Thread.currentThread().setName(Thread.currentThread().getName() + " :: Network Manager");
			try
			{
				selector = Selector.open();
				initialized.set(true);
				
				while( !Thread.currentThread().isInterrupted() )
					selector.select(this::onSelect);
			}
			catch(Exception e)
			{
				Manager.of(Logger.class).severe(Network.class, e);
			}
			finally
			{
				if( selector == null ) return;
				
				try
				{
					for( SelectionKey k : selector.keys() )
					{
						k.cancel();
						try { k.channel().close(); } catch(Exception e) { }
					}
				}
				catch(Exception e) { }
				
				try { selector.close(); } catch(Exception e) { }
			}
		});
	}
	
	protected Class<? extends DefaultNetwork.Implementation> defaultEntity() { return DefaultNetwork.Implementation.class; }
	protected Supplier<? extends DefaultNetwork.Implementation> defaultCreator() { return DefaultNetwork.Implementation::new; }
	
	public Template<? extends Network> template()
	{
		return super.template()
			.summary("Non-blocking network manager")
			.description("This network manager will keep track of all listening and established connections in a non-blocking efficient manner and will defer"
				+ "processing of reads and writes to the Execution manager. Connections can be secured with TLS.")
			.config(Network.class, new Parameter("timeout")
				.summary("Default network idle timeout")
				.description("This configuration parameter defines the priod of time in milliseconds after which an idle network connection is considered inactive ans should be forcibly closed.")
				.defaultValue(Data.of(120000)));
	}
	
	// =========================================
	//
	// CONNECTION / SECURE CONNECTION / SERVER
	//
	// =========================================
	
	private static class ConnectionImplementation implements Connection
	{
		ConcurrentLinkedQueue<byte[]> fifo = new ConcurrentLinkedQueue<byte[]>();
		private AtomicBoolean connected = new AtomicBoolean(false);
		private AtomicBoolean closed = new AtomicBoolean(false);
		
		public ConnectionImplementation(SocketChannel channel, boolean clientMode)
		{
			this.channel.set(channel);
			this.clientMode = clientMode;
		}
		
		public void close() throws IOException 
		{
			if( closed.get() ) return;
			
			try
			{
				closed.set(true);
				SelectableChannel c = channel.getAndSet(null);
				if( c != null ) c.close();
			}
			finally
			{
				Manager.of(Timeout.class).remove(tracker);
				onClose().trigger(this);
			}
		}

		private Callback<Connection> onReady = new Callback<>();
		public Callback<Connection> onReady() { return onReady; }

		public byte[] next() { return fifo.poll(); }
		
		public boolean hasNext() { return !fifo.isEmpty(); }

		public Task<Void> write(ByteBuffer data)
		{
			// TODO : decide if we write IO async. This allows to free the processing thread faster but adds more latency due to context-switch.
			//return Manager.of(Executor.class).io(() -> 
			//{
				try
				{
					while( !closed.get() && !connected.get() )
						LockSupport.parkNanos(10_000_000);
						
					SocketChannel c = channel();
					if( c == null ) throw new IllegalStateException("Connection is not established");
					
					synchronized(c)
					{
						while( data.hasRemaining() )
						{
							if( c.write(data) == 0 )
								LockSupport.parkNanos(1_000_000);
							else
								resetTimeout();
						}
					}
				}
				catch(IOException e)
				{
					try { close(); } catch(Exception ioe) { }
					throw new RuntimeException(e);
				}
			//});
			return Task.completed(null);
		}

		private Callback<Connection> onClose = new Callback<>();
		public Callback<Connection> onClose() { return onClose; }
		
		private AtomicReference<SocketChannel> channel = new AtomicReference<>();
		public SocketChannel channel() { return channel.get(); }
		
		private boolean clientMode;
		public boolean isClientMode() { return clientMode; }

		public boolean isSecure() { return false; }
		
		public void timeout(long ms) { ttl = Math.max(ms, 10); }
		
		public String clientIp()
		{
			try
			{
				if( isClientMode() )
					return ((InetSocketAddress) channel.get().getLocalAddress()).getAddress().getHostAddress();
				else
					return ((InetSocketAddress) channel.get().getRemoteAddress()).getAddress().getHostAddress();
			}
			catch(Exception e) { return "undefined"; }
		}
		
		public String serverIp()
		{
			try
			{
				if( isServerMode() )
					return ((InetSocketAddress) channel.get().getLocalAddress()).getAddress().getHostAddress();
				else
					return ((InetSocketAddress) channel.get().getRemoteAddress()).getAddress().getHostAddress();
			}
			catch(Exception e) { return "undefined"; }
		}
		
		public String alpn() { return ""; }
		
		public boolean active() { return !closed.get(); }
		
		// =========================================
		//
		// TIMEOUT TRACKER
		//
		// =========================================
		
		private volatile long lastActivity = System.currentTimeMillis();
		
		public void resetTimeout() { lastActivity = System.currentTimeMillis(); }
		
		private long ttl = Manager.of(Config.class).get(Network.class, "timeout").asLong();
		
		private Tracker<ConnectionImplementation> tracker = new Tracker<ConnectionImplementation>(this)
		{
			public long delay()
			{
				if( closed.get() ) return -1;
				return Math.max(0, lastActivity + ttl - System.currentTimeMillis());
			}
		};
		
		{
			tracker.onExpire().then((c) ->
			{
				SocketChannel channel = c.channel();

				try
				{
					String localAddress = ((InetSocketAddress) channel.getLocalAddress()).getAddress().getHostAddress();
					int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
					String remoteAddress = ((InetSocketAddress) channel.getRemoteAddress()).getAddress().getHostAddress();
					int remotePort = ((InetSocketAddress) channel.getRemoteAddress()).getPort();
					
					Manager.of(Logger.class).fine(Network.class, "Timeout for connection {}:{} -> {}:{}", localAddress, localPort, remoteAddress, remotePort);
					c.close();
				}
				catch(Exception e)
				{
					/* noop */
				}
			});
			Manager.of(Timeout.class).watch(tracker);
		}
	}
	
	private static class SecureConnectionImplementation implements Connection
	{
		private ConcurrentLinkedQueue<byte[]> fifo = new ConcurrentLinkedQueue<byte[]>();
		private Connection source;
		private AtomicBoolean handshaking = new AtomicBoolean(true);
		private SSLEngine ssl;
		
		private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

		public SecureConnectionImplementation(Connection source, SecurityOptions options)
		{
			this.source = source; 
			this.source.onClose().then((c) -> { this.onClose().trigger(this); });
			this.source.onReady().then((c) -> { this.read(); });
			
			ssl = Network.sslEngine(options, source.isClientMode());
			if( source.isClientMode() ) handshake(null);
		}
		
		private void handshake(ByteBuffer read)
		{
			try
			{
				SSLEngineResult status = null;
				
				handshakeloop: while( true )
				{
					HandshakeStatus step = (status == null ? ssl.getHandshakeStatus() : status.getHandshakeStatus());
					switch( step )
					{
						case NOT_HANDSHAKING:
						{
							if( read != null && status == null ) // this is the first CLIENT_HELLO packet
							{
								status = ssl.unwrap(read, EMPTY);
								break;
							}
							// else continue to FINISHED
						}
						case FINISHED:
						{
							if( !ssl.getSession().isValid() || ssl.getSession().getId().length == 0 )
								throw new SSLHandshakeException("Handshake failed");
							
							// prevent rejoin session because it keeps a cache for almost never used rejoin
							ssl.getSession().invalidate();
							handshaking.set(false);

							if( read != null && read.hasRemaining() )
							{
								if( reading.get() )
									read3(); // continue with remaining buffer
								else
									read(); // re-acquire read lock
							}
							return;
						}
						case NEED_TASK:
						{
							Runnable task;
							while((task = ssl.getDelegatedTask()) != null )
								task.run();
							status = null;
							break;
						}
						case NEED_WRAP:
						{
							try( TLS_Buffer encrypted = TLS_BufferPool.poll() )
							{
								status = ssl.wrap(EMPTY, encrypted.get());
								source.write(encrypted.get().flip());
							}
							break;
						}
						case NEED_UNWRAP:
						case NEED_UNWRAP_AGAIN:
						{
							if( read == null || !read.hasRemaining() )
								break handshakeloop;
							else
								status = ssl.unwrap(read, EMPTY);
							break;
						}
						default: throw new SSLHandshakeException("Unsupported handshake status: " + ssl.getHandshakeStatus());
					}
				}
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		}

		public void close() throws IOException { source.close(); }

		public boolean isSecure() { return true; }

		public boolean isClientMode() { return source.isClientMode(); }

		private Callback<Connection> onReady = new Callback<>();
		public Callback<Connection> onReady() { return onReady; }

		public byte[] next() { return fifo.poll(); }
		
		public boolean hasNext() { return !fifo.isEmpty(); }

		AtomicBoolean reading = new AtomicBoolean(false);
		TLS_Buffer encrypted = null;

		private void read()
		{
			// this is step one : we set the reading flag to true
			// then call read2()
			// then reset the reading flag to false
			while( source.hasNext() )
			{
				if (!reading.compareAndSet(false, true)) return;

				try
				{
					read2();
				}
				catch(Exception e)
				{
					Manager.of(Logger.class).fine(Network.class, e);
					try { close(); } catch(Exception x) { }
				}
				finally
				{
					reading.set(false);
				}
			}
		}
		
		private void read2() throws Exception
		{
			// this is step two : we read all data and fill the encrypted buffer
			// then call read3()
			// then advertise if we published anything (ready)
			
			for( byte[] data = source.next(); data != null; data = source.next() )
			{
				if( encrypted == null ) encrypted = TLS_BufferPool.poll();
				
				// maybe we cannot fit all data in the encrypted buffer
				// so put what we can now. IN ALL CASES, either we can fit all data,
				// or it will free some space after unwrap and we can fit the remaining data.
				
				boolean partial = false;
				do
				{
					int mark = 0;
					if( data.length > encrypted.get().remaining() )
					{
						partial = true;
						int limit = encrypted.get().remaining();
						encrypted.get().put(data, mark, Math.min(limit, data.length - mark));
						mark = limit;
					}
					else
					{
						encrypted.get().put(data);
						partial = false;
					}
					encrypted.get().flip();
					
					read3();
				} while( partial );
			}
			
			if( !encrypted.get().hasRemaining() )
			{
				encrypted.close();
				encrypted = null;
			}
		}
		
		private void read3() throws Exception
		{
			if( handshaking.get() )
			{
				handshake(encrypted.get());
				encrypted.get().compact();
				return;
			}
			
			// this is step three : we do the unwrap
			boolean wasok = false;
			do
			{
				wasok = false;
				
				try( TLS_Buffer decrypted = TLS_BufferPool.poll() )
				{
					SSLEngineResult status = ssl.unwrap(encrypted.get(), decrypted.get());
					
					if( status.getHandshakeStatus() == HandshakeStatus.NEED_TASK )
					{
						// handshake task can happen at any time
						Runnable task;
						while((task = ssl.getDelegatedTask()) != null )
							task.run();
					}
					
					switch( status.getStatus() )
					{
						case BUFFER_UNDERFLOW: // not enough input data
						{
							break;
						}
						case OK: // frame is ok
						{
							wasok = true;
							decrypted.get().flip();
							if( decrypted.get().hasRemaining() )
							{
								byte[] d = new byte[decrypted.get().remaining()]; 
								decrypted.get().get(d);
								fifo.offer(d);
								encrypted.get().compact();
								encrypted.get().flip();
							}
							decrypted.get().clear();

							onReady().trigger(this);
							
							// loop to check for another frame
							break;
						}
						case BUFFER_OVERFLOW: // not enough space in output buffer
						{
							// This is not possible because we sized the buffer based on max SSL frame size
							throw new IllegalStateException("Unexpected large frame size");
						}
						default:
						case CLOSED:
						{
							return; // nothing more
						}
					}
				} // return decrypted buffer to the pool
			} while( wasok );
		}
		
		public Task<Void> write(ByteBuffer data)
		{
			Task<Void> io = null;
			
			while( handshaking.get() )
				LockSupport.parkNanos(10_000_000);

			while( data.hasRemaining() )
			{
				try
				{
					if( io != null ) io.await();
					
					try (TLS_Buffer buffer = TLS_BufferPool.poll() )
					{
						ByteBuffer encrypted = buffer.get();
						SSLEngineResult result = ssl.wrap(data, encrypted);
						
						if( result.getStatus() != SSLEngineResult.Status.OK )
							throw new IllegalStateException("Invalid SSLEngine state in wrap : " + result.getStatus());
						
						if( result.getHandshakeStatus() == HandshakeStatus.NEED_TASK )
						{
							// handshake task can happen at any time
							Runnable task;
							while((task = ssl.getDelegatedTask()) != null )
								task.run();
						}
						
						io = source.write(encrypted.flip());
					}
				}
				catch(Exception e) { throw new RuntimeException(e); }
			}
			
			return io == null ? Task.completed(null) : io;
		}

		public Callback<Connection> onClose() { return source.onClose(); }
		
		public void timeout(long ms) { source.timeout(ms); }
		
		public String clientIp() { return source.clientIp(); }
		
		public String serverIp() { return source.serverIp(); }
		
		public String alpn()
		{
			if( ssl == null ) return "";
			return Objects.requireNonNullElse(ssl.getApplicationProtocol(), "");
		}
		
		public boolean active() { return source.active(); }
	}
	
	private static class ServerImplementation implements Server
	{
		public ServerImplementation(ServerSocketChannel channel, SecurityOptions security)
		{
			this.channel.set(channel);
			this.security = security;
		}
		
		public void close() throws IOException
		{
			try
			{
				SelectableChannel c = channel.getAndSet(null);
				if( c != null ) c.close();
			}
			finally
			{
				onClose().trigger(this);
			}
		}

		private Callback<Connection> onAccept = new Callback<>();
		public Callback<Connection> onAccept() { return onAccept; }

		private Callback<Server> onClose = new Callback<>();
		public Callback<Server> onClose() { return onClose; }
		
		private AtomicReference<ServerSocketChannel> channel = new AtomicReference<>();
		public ServerSocketChannel channel() { return channel.get(); }
		
		private SecurityOptions security = null;
		public SecurityOptions security() { return security; }
		
		public boolean isSecure() { return security() != null; }
	}
	
	// =========================================
	//
	// SHARED BYTEBUFFER POOL FOR TLS
	//
	// =========================================
	
	private static class TLS_BufferPool
	{
		private static Deque<TLS_Buffer> pool = new ConcurrentLinkedDeque<>();
		
		static void offer(TLS_Buffer buffer) { buffer.get().clear(); pool.offerFirst(buffer); }
		
		private static AtomicBoolean initialized = new AtomicBoolean(false);
		
		static TLS_Buffer poll()
		{
			if( initialized.compareAndSet(false, true) )
				Manager.of(Timeout.class).watch(tracker);
			
			TLS_Buffer buffer = pool.pollFirst();
			if( buffer == null ) return new TLS_Buffer();
			else return buffer;
		}
		
		// this is a fake timeout tracker because it will never expire.
		// instead, we perform the cleanup logic directly here because it is cheap
		private static Tracker<Void> tracker = new Tracker<Void>(null)
		{
			private long max = 60_000_000; // 1min
			public long delay()
			{
				if( pool.size() == 0 ) return max;
				
				// by design, the last element of the queue is the oldest
				Iterator<TLS_Buffer> i = pool.descendingIterator();
				while( i.hasNext() )
				{
					TLS_Buffer buffer = i.next();
					if( buffer == null ) continue;
					
					// expired item, remove it
					if( (System.currentTimeMillis() - buffer.idle) >= max ) { i.remove(); continue; }
					
					// otherwise come back later
					return Math.max(1, (buffer.idle + max) - System.currentTimeMillis());
				}
				return max;
			}
		};
	}
	
	private static class TLS_Buffer implements AutoCloseable, Supplier<ByteBuffer>
	{
		private long idle = System.currentTimeMillis();
		private ByteBuffer buffer;
		public TLS_Buffer()
		{
			Hardware.RAM.waitForSpace(50*1024, 1000);
			
			// RFC 2246 (section 6.2. 2) : max size of plain text data is 16KB
			// when encrypted it can be higher, so we use 50KB to be sure
			buffer = ByteBuffer.allocateDirect(50*1024);
		}
		
		public ByteBuffer get() { return buffer; }
		public void close()
		{
			idle = System.currentTimeMillis();
			TLS_BufferPool.offer(this);
		}
	}
}
