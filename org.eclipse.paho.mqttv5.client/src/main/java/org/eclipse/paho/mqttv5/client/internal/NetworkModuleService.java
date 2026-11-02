/*
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 * https://www.eclipse.org/legal/epl-2.0
 * and the Eclipse Distribution License is available at
 * https://www.eclipse.org/org/documents/edl-v10.php
 */
package org.eclipse.paho.mqttv5.client.internal;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.logging.Logger;
import org.eclipse.paho.mqttv5.client.logging.LoggerFactory;
import org.eclipse.paho.mqttv5.client.spi.NetworkModuleFactory;
import org.eclipse.paho.mqttv5.common.MqttException;

/**
 * The NetworkModuleService uses the installed {@link NetworkModuleFactory}s to create {@link NetworkModule} instances.
 * <p>
 * The selection of the appropriate NetworkModuleFactory is based on the URI scheme.
 *
 * @author Maik Scheibler
 */
public class NetworkModuleService {
	private static Logger LOG = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT,
			NetworkModuleService.class.getSimpleName());
	private static final ServiceLoader<NetworkModuleFactory> FACTORY_SERVICE_LOADER = ServiceLoader.load(
			NetworkModuleFactory.class, NetworkModuleService.class.getClassLoader());

	/** Pattern to match URI authority parts: {@code authority = [userinfo"@"]host[":"port]} */
	private static final Pattern AUTHORITY_PATTERN = Pattern.compile("((.+)@)?([^:]*)(:(\\d+))?");
	private static final int AUTH_GROUP_USERINFO = 2;
	private static final int AUTH_GROUP_HOST = 3;
	private static final int AUTH_GROUP_PORT = 5;

	private NetworkModuleService() {
		// no instances
	}

	/**
	 * Validates the provided URI to be valid and that a NetworkModule is installed to serve it.
	 *
	 * @param brokerUri to be validated
	 * @throws IllegalArgumentException is case the URI is invalid or there is no {@link NetworkModule} installed for
	 * the URI scheme
	 */
	public static void validateURI(String brokerUri) throws IllegalArgumentException {
		try {
			URI uri = new URI(brokerUri);
			String scheme = uri.getScheme();
			if (scheme == null || scheme.isEmpty()) {
				throw new IllegalArgumentException("missing scheme in broker URI: " + brokerUri);
			}
			scheme = scheme.toLowerCase();
			synchronized (FACTORY_SERVICE_LOADER) {
				for (NetworkModuleFactory factory : FACTORY_SERVICE_LOADER) {
					if (factory.getSupportedUriSchemes().contains(scheme)) {
						factory.validateURI(uri);
						return;
					}
				}
			}
			throw new IllegalArgumentException("no NetworkModule installed for scheme \"" + scheme
					+ "\" of URI \"" + brokerUri + "\"");
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Can't parse string to URI \"" + brokerUri + "\"", e);
		}
	}

	/**
	 * Creates a {@link NetworkModule} instance for the provided address, using the given options.
	 *
	 * @param address must be a valid URI
	 * @param options used to initialize the NetworkModule
	 * @param clientId a client identifier that is unique on the server being connected to
	 * @return a new NetworkModule instance
	 * @throws MqttException if the initialization fails
	 * @throws IllegalArgumentException if the provided {@code address} is invalid
	 */
	public static NetworkModule createInstance(String address, MqttConnectionOptions options, String clientId)
			throws MqttException, IllegalArgumentException
	{
		try {
			URI brokerUri = new URI(address);
			applyRFC3986AuthorityPatch(brokerUri);
			String scheme = brokerUri.getScheme().toLowerCase();
			synchronized (FACTORY_SERVICE_LOADER) {
				for (NetworkModuleFactory factory : FACTORY_SERVICE_LOADER) {
					if (factory.getSupportedUriSchemes().contains(scheme)) {
						return factory.createNetworkModule(brokerUri, options, clientId);
					}
				}
			}
			/*
			 * To throw an IllegalArgumentException exception matches the previous behavior of
			 * MqttConnectOptions.validateURI(String), but it would be nice to provide something more meaningful.
			 */
			throw new IllegalArgumentException(brokerUri.toString());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(address, e);
		}
	}

	/**
	 * Java does URI parsing according to RFC2396 and thus hostnames are limited to alphanumeric characters and '-'.
	 * But the current &quot;Uniform Resource Identifier (URI): Generic Syntax&quot; (RFC3986) allows for a much wider
	 * range of valid characters. This causes Java to fail parsing the authority part and thus the user-info, host and
	 * port will not be set on an URI which does not conform to RFC2396.
	 * <p>
	 * This workaround tries to detect such a parsing failure and does tokenize the authority parts according to
	 * RFC3986, but does not enforce any character restrictions (for sake of simplicity).
	 *
	 * @param toPatch
	 * @see https://tools.ietf.org/html/rfc3986#section-3.2
	 */
	static void applyRFC3986AuthorityPatch(URI toPatch) {
		if (toPatch == null
				|| toPatch.getHost() != null // already successfully parsed
				|| toPatch.getAuthority() == null
				|| toPatch.getAuthority().isEmpty())
		{
			return;
		}
		Matcher matcher = AUTHORITY_PATTERN.matcher(toPatch.getAuthority());
		if (matcher.find()) {
			setURIField(toPatch, "userInfo", matcher.group(AUTH_GROUP_USERINFO));
			setURIField(toPatch, "host", matcher.group(AUTH_GROUP_HOST));
			String portString = matcher.group(AUTH_GROUP_PORT);
			setURIField(toPatch, "port", portString != null ? Integer.parseInt(portString) : -1);
		}
	}

	/**
	 * Reflective manipulation of a URI field to work around the URI parser, because all fields are validated even on
	 * the full qualified URI constructor.
	 *
	 * @see URI#URI(String, String, String, int, String, String, String)
	 */
	/**
	 * Returns the host of a broker URI. {@link URI} implements RFC 2396, which does not allow
	 * {@code _} in host names; for such an authority {@link URI#getHost()} is {@code null} although
	 * RFC 3986 allows it. The authority is then parsed here instead. This replaces patching the
	 * private fields of the URI, which Java 16 and later deny.
	 *
	 * @param uri the broker URI
	 * @return the host, or {@code null} if the URI has none
	 */
	public static String getHost(URI uri) {
		Matcher matcher = matchRegistryAuthority(uri);
		return matcher == null ? uri.getHost() : matcher.group(AUTH_GROUP_HOST);
	}

	/**
	 * Returns the port of a broker URI, see {@link #getHost(URI)}.
	 *
	 * @param uri the broker URI
	 * @return the port, or -1 if the URI has none
	 */
	public static int getPort(URI uri) {
		Matcher matcher = matchRegistryAuthority(uri);
		if (matcher == null) {
			return uri.getPort();
		}
		String portString = matcher.group(AUTH_GROUP_PORT);
		return portString != null ? Integer.parseInt(portString) : -1;
	}

	/**
	 * Returns the user information of a broker URI, see {@link #getHost(URI)}.
	 *
	 * @param uri the broker URI
	 * @return the user information, or {@code null} if the URI has none
	 */
	public static String getUserInfo(URI uri) {
		Matcher matcher = matchRegistryAuthority(uri);
		return matcher == null ? uri.getUserInfo() : matcher.group(AUTH_GROUP_USERINFO);
	}

	/** Matches the authority of a URI whose host {@link URI} could not parse, else {@code null}. */
	private static Matcher matchRegistryAuthority(URI uri) {
		if (uri.getHost() != null || uri.getAuthority() == null || uri.getAuthority().isEmpty()) {
			return null;
		}
		Matcher matcher = AUTHORITY_PATTERN.matcher(uri.getAuthority());
		return matcher.find() ? matcher : null;
	}

	private static void setURIField(URI toManipulate, String fieldName, Object newValue) {
		try {
			Field field = URI.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(toManipulate, newValue);
		} catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
			// RuntimeException: Java 16+ throws InaccessibleObjectException; the built-in network
			// modules do not depend on the patch, they use getHost/getPort/getUserInfo(URI).
			LOG.warning(NetworkModuleService.class.getName(), "setURIField", "115", new Object[] {
					toManipulate.toString() }, e);
		}
	}
}
