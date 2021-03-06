/*
 * ESP-OpenBCI Copyright (C) 2014 Burton Alexander
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 * 
 */
package com.github.mrstampy.esp.openbci;

import static com.github.mrstampy.esp.openbci.OpenBCIChannelCommand.getFromChannelNumber;
import static com.github.mrstampy.esp.openbci.OpenBCIProperties.getBooleanProperty;
import static com.github.mrstampy.esp.openbci.OpenBCIProperties.getIntegerProperty;
import static com.github.mrstampy.esp.openbci.OpenBCIProperties.getProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javolution.util.FastList;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.serial.SerialAddress;
import org.apache.mina.transport.serial.SerialAddress.DataBits;
import org.apache.mina.transport.serial.SerialAddress.FlowControl;
import org.apache.mina.transport.serial.SerialAddress.Parity;
import org.apache.mina.transport.serial.SerialAddress.StopBits;
import org.apache.mina.transport.serial.SerialConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;
import rx.Scheduler.Inner;
import rx.Subscription;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import com.github.mrstampy.esp.multiconnectionsocket.AbstractMultiConnectionSocket;
import com.github.mrstampy.esp.multiconnectionsocket.EspChannel;
import com.github.mrstampy.esp.multiconnectionsocket.MultiConnectionSocketException;
import com.github.mrstampy.esp.openbci.rxtx.RxtxDataBuffer;
import com.github.mrstampy.esp.openbci.rxtx.RxtxNativeLibLoader;
import com.github.mrstampy.esp.openbci.subscription.OpenBCIEvent;
import com.github.mrstampy.esp.openbci.subscription.OpenBCIEventListener;

// TODO: Auto-generated Javadoc
/**
 * The Class MultiConnectOpenBCISocket.
 */
public class MultiConnectOpenBCISocket extends AbstractMultiConnectionSocket<byte[]> implements OpenBCIConstants {
	private static final Logger log = LoggerFactory.getLogger(MultiConnectOpenBCISocket.class);

	// load the RXTX native library
	static {
		try {
			initRxtx();
		} catch (Exception e) {
			log.error("Unexpected exception loading RXTX native library", e);
			throw new RuntimeException(e);
		}
	}

	private static void initRxtx() throws IOException {
		if (getBooleanProperty("rxtx.lib.installed")) return;

		String osName = getProperty("os.override.name");
		String osArch = getProperty("os.override.arch");

		if (isEmpty(osArch) && isEmpty(osName)) {
			RxtxNativeLibLoader.loadRxtxSerialNativeLib();
		} else {
			RxtxNativeLibLoader.loadRxtxSerialNativeLib(osName, osArch);
		}
	}

	private static boolean isEmpty(String s) {
		return s == null || s.trim().length() == 0;
	}

	private SerialConnector connector;

	private List<OpenBCIEventListener> listeners = new FastList<OpenBCIEventListener>();

	private OpenBCISubscriptionHandlerAdapter subscriptionHandlerAdapter;

	private Map<Integer, SampleBuffer> samples = new ConcurrentHashMap<Integer, SampleBuffer>();

	private Scheduler scheduler = Schedulers.executor(Executors.newScheduledThreadPool(5));
	private Subscription subscription;

	private OpenBCICommand startCommand = OpenBCICommand.START_BINARY;

	/**
	 * Instantiates a new multi connect open bci socket.
	 *
	 * @throws IOException
	 *           Signals that an I/O exception has occurred.
	 */
	public MultiConnectOpenBCISocket() throws IOException {
		this(false);
	}

	/**
	 * Instantiates a new multi connect open bci socket.
	 *
	 * @param broadcasting
	 *          the broadcasting
	 * @throws IOException
	 *           Signals that an I/O exception has occurred.
	 */
	public MultiConnectOpenBCISocket(boolean broadcasting) throws IOException {
		super(broadcasting);
		setNumChannels(getIntegerProperty("esp.openbci.num.channels"));
		initConnector();
		initSampleBuffers();
	}

	/**
	 * Send command.
	 *
	 * @param command
	 *          the command
	 */
	public void sendCommand(OpenBCICommand command) {
		if (command == null || !isConnected()) {
			log.warn("Cannot send command {}", command);
			return;
		}

		connector.broadcast(command.getCommand());
	}

	/**
	 * Activate channel.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void activateChannel(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send activate for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getActivate());
	}

	/**
	 * Deactivate channel.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void deactivateChannel(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send deactivate for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getDeactivate());
	}

	/**
	 * Activate leadoff n.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void activateLeadoffN(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send activateLeadoffN for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getActivateLeadoffN());
	}

	/**
	 * Activate leadoff p.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void activateLeadoffP(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send activateLeadoffP for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getActivateLeadoffP());
	}

	/**
	 * Deactivate leadoff n.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void deactivateLeadoffN(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send deactivateLeadoffN for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getDeactivateLeadoffN());
	}

	/**
	 * Deactivate leadoff p.
	 *
	 * @param channelNumber
	 *          the channel number
	 */
	public void deactivateLeadoffP(int channelNumber) {
		if (!isConnected()) {
			log.warn("Cannot send deactivateLeadoffP for {}", channelNumber);
			return;
		}

		connector.broadcast(getFromChannelNumber(channelNumber).getDeactivateLeadoffP());
	}

	/**
	 * Adds the listener.
	 *
	 * @param l
	 *          the l
	 */
	public void addListener(OpenBCIEventListener l) {
		if (l != null && !listeners.contains(l)) listeners.add(l);
	}

	/**
	 * Removes the listener.
	 *
	 * @param l
	 *          the l
	 */
	public void removeListener(OpenBCIEventListener l) {
		if (l != null) listeners.remove(l);
	}

	/**
	 * Clear listeners.
	 */
	public void clearListeners() {
		listeners.clear();
	}

	/**
	 * When invoked the tuning functionality of the {@link SampleBuffer} will be
	 * activated. The tuning process takes ~ 10 seconds, during which the number
	 * of samples will be counted and used to resize the buffer to more closely
	 * represent 1 seconds' worth of data.
	 */
	public void tune() {
		if (!isConnected()) {
			log.warn("Must be connected to the OpenBCI hardware to tune");
			return;
		}

		log.info("Tuning sample buffer");

		for (SampleBuffer sampleBuffer : samples.values()) {
			sampleBuffer.tune();
		}

		scheduler.schedule(new Action1<Scheduler.Inner>() {

			@Override
			public void call(Inner t1) {
				for (SampleBuffer sampleBuffer : samples.values()) {
					sampleBuffer.stopTuning();
				}
			}
		}, 10, TimeUnit.SECONDS);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.github.mrstampy.esp.multiconnectionsocket.MultiConnectionSocket#isConnected
	 * ()
	 */
	@Override
	public boolean isConnected() {
		return connector.isActive();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.github.mrstampy.esp.multiconnectionsocket.AbstractMultiConnectionSocket
	 * #startImpl()
	 */
	@Override
	protected void startImpl() throws MultiConnectionSocketException {
		String error = "Could not connect to OpenBCI hardware";

		try {
			ConnectFuture cf = connector.connect(getSerialAddress());
			cf.await(5000);
			if (!cf.isConnected()) throw new MultiConnectionSocketException(error);
			sendCommand(getStartCommand());
		} catch (InterruptedException e) {
			log.error(error, e);
			throw new MultiConnectionSocketException(error, e);
		}

		scheduleSampling();
	}

	private void initSampleBuffers() {
		if (getNumChannels() <= 0) throw new IllegalArgumentException("esp.openbci.num.channels property must be > 0");

		for (int i = 1; i <= getNumChannels(); i++) {
			samples.put(i, new SampleBuffer(i));
		}
	}

	private void scheduleSampling() {
		OpenBCIDSPValues values = OpenBCIDSPValues.getInstance();
		long pause = 500l;
		long snooze = values.getSampleRateSleepTime();
		TimeUnit tu = values.getSampleRateUnits();

		subscription = scheduler.schedulePeriodically(new Action1<Scheduler.Inner>() {

			@Override
			public void call(Inner t1) {
				for (Entry<Integer, SampleBuffer> entry : samples.entrySet()) {
					processSnapshot(entry.getValue().getSnapshot(), entry.getKey());
				}
			}
		}, pause, snooze, tu);
	}

	private void processSnapshot(double[] snapshot, int channelNumber) {
		final int cn = channelNumber;
		Observable.just(snapshot).subscribe(new Action1<double[]>() {

			@Override
			public void call(double[] snap) {
				notifyListeners(snap, cn);
				if (canBroadcast()) subscriptionHandlerAdapter.sendMultiConnectionEvent(new OpenBCIEvent(snap, cn));
			}
		});
	}

	private void notifyListeners(double[] snapshot, int channelNumber) {
		if (listeners.isEmpty()) return;

		OpenBCIEvent event = new OpenBCIEvent(snapshot, channelNumber);
		for (OpenBCIEventListener l : listeners) {
			l.dataEventPerformed(event);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.github.mrstampy.esp.multiconnectionsocket.AbstractMultiConnectionSocket
	 * #stopImpl()
	 */
	@Override
	protected void stopImpl() {
		if (!isConnected()) return;

		try {
			sendCommand(OpenBCICommand.STOP);
			connector.dispose();
		} finally {
			if (subscription != null) subscription.unsubscribe();
			initConnector();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.github.mrstampy.esp.multiconnectionsocket.AbstractMultiConnectionSocket
	 * #getHandlerAdapter()
	 */
	@Override
	protected IoHandler getHandlerAdapter() {
		subscriptionHandlerAdapter = new OpenBCISubscriptionHandlerAdapter(this);
		return subscriptionHandlerAdapter;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.github.mrstampy.esp.multiconnectionsocket.AbstractMultiConnectionSocket
	 * #parseMessage(java.lang.Object)
	 */
	@Override
	protected void parseMessage(byte[] message) {
		Observable.just(message).subscribe(new Action1<byte[]>() {

			@Override
			public void call(byte[] t1) {
				int numChannels = getNumChannels(t1);

				if (!isValidMessage(t1, numChannels)) {
					log.error("Invalid message received, expected {} channels (length {}) but message length was {}",
							numChannels, getExpectedLength(numChannels), t1.length);
					return;
				}

				int idx = CHANNELS_START_IDX; // start of samples
				for (int channelNumber = 1; channelNumber <= numChannels; channelNumber++) {
					samples.get(channelNumber).addSample(getSample(t1, idx));
					idx += 4;
				}
			}

			private byte[] getSample(byte[] t1, int idx) {
				byte[] sample = new byte[4];

				System.arraycopy(t1, idx, sample, 0, 4);

				return sample;
			}

			// start, stop & num channels + data
			private int getExpectedLength(int numChannels) {
				return 3 + (4 * numChannels);
			}

			private boolean isValidMessage(byte[] t1, int numChannels) {
				return getExpectedLength(numChannels) == t1.length;
			}

			private int getNumChannels(byte[] t1) {
				return ((int) t1[NUM_CHANNELS_IDX]) / 4 - 1;
			}
		});
	}

	private void initConnector() {
		connector = new SerialConnector();
		connector.setHandler(new IoHandlerAdapter() {

			private RxtxDataBuffer buffer = new RxtxDataBuffer();

			@Override
			public void messageReceived(IoSession session, Object message) throws Exception {
				byte[] msg = (byte[]) message;

				boolean complete = isCompleteMessage(msg);

				if (!complete) buffer.add(msg);

				publishBufferedMessages();

				if (complete) publishMessage(msg);
			}

			private void publishBufferedMessages() {
				byte[] m = buffer.get();
				while (m != null) {
					publishMessage(m);
					m = buffer.get();
				}
			}

			private boolean isCompleteMessage(byte[] message) {
				if (message[0] != START_PACKET) return false;

				for (int i = 1; i < message.length; i++) {
					if (message[i] == END_PACKET) {
						return i == message.length - 1;
					}
				}

				return false;
			}

			@Override
			public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
				log.error("Unexpected RXTX exception", cause);
			}
		});
	}

	private SerialAddress getSerialAddress() {
		String portIdentifier = getProperty("port.identifier");
		int baudRate = getIntegerProperty("baud.rate");
		DataBits dataBits = getDataBits();
		StopBits stopBits = getStopBits();
		Parity parity = getParity();
		FlowControl flowControl = getFlowControl();

		return new SerialAddress(portIdentifier, baudRate, dataBits, stopBits, parity, flowControl);
	}

	private FlowControl getFlowControl() {
		String fc = getProperty("flow.control");

		return FlowControl.valueOf(fc);
	}

	private Parity getParity() {
		String p = getProperty("parity");

		return Parity.valueOf(p);
	}

	private StopBits getStopBits() {
		String sbs = getProperty("stop.bits");

		return StopBits.valueOf(sbs);
	}

	private DataBits getDataBits() {
		String dbs = getProperty("data.bits");

		return DataBits.valueOf(dbs);
	}

	/**
	 * Gets the start command. Defaults to START_BINARY.
	 *
	 * @return the start command
	 */
	public OpenBCICommand getStartCommand() {
		return startCommand;
	}

	/**
	 * Sets the start command.
	 *
	 * @param startCommand
	 *          the new start command
	 */
	public void setStartCommand(OpenBCICommand startCommand) {
		switch (startCommand) {
		case START_BINARY:
		case START_BINARY_4CHAN:
		case START_BINARY_WAUX:
			break;
		default:
			log.error("startCommand must be one of the 'START_BINARY' commands");
			return;
		}
		this.startCommand = startCommand;
	}

	@Override
	public List<EspChannel> getChannels() {
		List<EspChannel> channels = new ArrayList<EspChannel>();

		for (int i = 1; i <= getNumChannels(); i++) {
			channels.add(getChannel(i));
		}

		return channels;
	}

	@Override
	public EspChannel getChannel(int channelNumber) {
		if (channelNumber < 1 || channelNumber > getNumChannels()) {
			log.warn("Invalid channel number {}. Values must be between 1 and {} inclusive", channelNumber, getNumChannels());
			return null;
		}

		String desc = getProperty("esp.openbci.channel" + channelNumber + ".desc");
		return new EspChannel(channelNumber, isEmpty(desc) ? "OpenBCI Channel " + channelNumber : desc);
	}

}
