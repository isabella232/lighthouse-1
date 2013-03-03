package com.logentries.lighthouse;

import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.Map;
import org.json.simple.JSONValue;
import org.json.simple.JSONObject;

/**
 * Logentries client for Lighthouse server.
 * 
 * VERSION: 1.0.0
 * 
 * @author Daniel Fiala
 * 
 */
public class LighthouseClient {
	static final Charset HTTP_CHARSET = Charset.forName("utf-8");

	static final String PATH_STATE = "state";
	static final String PATH_DATA = "data";
	static final String PATH_COPY = "copy";
	static final String PATH_LOCK = "lock";
	static final String PATH_UPDATE = "update";

	private String mHost;
	private int mPort;

	private String mLockKey = null;  // Our key if we acquired a lock

	private enum ReqType {
		REQ_PLAIN,
		REQ_JSON,
	};

	public LighthouseClient(final String host, final int port) {
		mHost = host;
		mPort = port;
	}

	private String url(final String path) {
		return String.format("http://%s:%d/%s", mHost, mPort, path);
	}

	private String readAll(final InputStreamReader reader) throws java.io.IOException {
		char[] buf = new char[4096];
		StringBuilder sb = new StringBuilder();
		for (int len; -1 != (len = reader.read(buf, 0, buf.length)); ) {
			sb.append(buf, 0, len);
		}
		return sb.toString();
	}

	private void checkResponseCode(final int rc, final String url) throws LighthouseException {
		if (rc == 403) {
			throw new AccessDeniedLighthouseException(url);
		}
		if (rc == 404) {
			throw new FileNotFoundLighthouseException(url);
		}
		if (rc == 409) {
			throw new ResponseConflictLighthouseException(url);
		}
		if (rc != 200 && rc != 201) {
			throw new BadStatusCodeLighthouseException(rc);
		}
	}

	private Object req_get(final String path, final ReqType reqType) throws LighthouseException {
		final String u = url(path);
		HttpURLConnection conn = null;
                InputStreamReader reader = null;
		try {
			conn = (HttpURLConnection)new URL(u).openConnection();
			int len = conn.getContentLength();
			reader = new InputStreamReader(conn.getInputStream(), HTTP_CHARSET);
			return reqType == ReqType.REQ_PLAIN ? readAll(reader) : JSONValue.parseWithException(reader);
		} catch (java.net.MalformedURLException e) {
			throw new LighthouseException(e);
		} catch (java.io.FileNotFoundException e) {
			throw new FileNotFoundLighthouseException(u, e);
		} catch (java.io.IOException e) {
			throw new LighthouseException(e);
		} catch (org.json.simple.parser.ParseException e) {
			throw new LighthouseException(e);
		} finally {
			try {
				if (null != reader) {
					reader.close();
				}
			} catch (java.io.IOException e) {
			}
			if (null != conn) {
				conn.disconnect();
			}
		}
	}

	// FIXME: Set content-type
	private int req_put(final String path, final Object obj, final ReqType reqType) throws LighthouseException {
		final String u = url(path);
		HttpURLConnection conn = null;
		OutputStreamWriter writer = null;
		try {
			conn = (HttpURLConnection) new URL(u).openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("PUT");
			writer = new OutputStreamWriter(conn.getOutputStream(), HTTP_CHARSET);
			if (reqType == ReqType.REQ_PLAIN) {
				final String str = obj.toString();
				writer.write(str, 0, str.length());
			} else {
				JSONValue.writeJSONString(obj, writer);
			}
			writer.flush();
			writer.close();
			writer = null;
			conn.connect();
			final int rc = conn.getResponseCode(); // It seems that the request is not performed without this call
			checkResponseCode(rc, u);
			return rc;
		} catch (java.io.FileNotFoundException e) {
			throw new FileNotFoundLighthouseException(u, e);
		} catch (java.io.IOException e) {
			throw new LighthouseException(e);
		} finally {
			try {
				if (null != writer) {
					writer.close();
				}
			} catch (java.io.IOException e) {
			}
			if (null != conn) {
				conn.disconnect();
			}
		}
	}

	private int req_delete(final String path) throws LighthouseException {
		final String u = url(path);
		HttpURLConnection conn = null;
		OutputStreamWriter writer = null;
		try {
			conn = (HttpURLConnection) new URL(u).openConnection();
			conn.setDoOutput(false);
			conn.setRequestMethod("DELETE");
			conn.connect();
			final int rc = conn.getResponseCode(); // It seems that the request is not performed without this call
			checkResponseCode(rc, u);
			return rc;
		} catch (java.io.FileNotFoundException e) {
			throw new FileNotFoundLighthouseException(u, e);
		} catch (java.io.IOException e) {
			throw new LighthouseException(e);
		} finally {
			try {
				if (null != writer) {
					writer.close();
				}
			} catch (java.io.IOException e) {
			}
			if (null != conn) {
				conn.disconnect();
			}
		}
	}

	public State state() throws LighthouseException {
		return State.fromMap((Map)req_get(PATH_STATE, ReqType.REQ_JSON));
	}

	public Object data(final String tail) throws LighthouseException {
		final Object ans = req_get(PATH_DATA + tail, ReqType.REQ_JSON);
		return ans;
	}

	private String updatePath(final String tail) throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		return String.format("%s/%s/%s", PATH_UPDATE, mLockKey, tail);
	}

	public void update(final String tail, final Object data) throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		final String path = updatePath(tail);
		final int code = req_put(path, data, ReqType.REQ_JSON);
	}

	public Object update(final String tail) throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		return req_get(updatePath(tail), ReqType.REQ_JSON);
	}

	public void deleteX(final String tail) throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		final String path = updatePath(tail);
		req_delete(path);
	}

	public Copy copy() throws LighthouseException {
		final Object ans = req_get(PATH_COPY, ReqType.REQ_JSON);
		final Map map = (Map)ans;
		return Copy.fromMap(map);
	}

	public void copy(final Copy x) throws LighthouseException {
		final int code = req_put(PATH_COPY, Copy.toMap(x), ReqType.REQ_JSON);
	}

	public void acquireLock(final String lockKey) throws LighthouseException {
		if (null == lockKey) {
			throw new IllegalArgumentException("lockKey cannot be null");
		}
		try {
			req_put(PATH_LOCK, lockKey, ReqType.REQ_PLAIN);
		} catch (AccessDeniedLighthouseException e) {
			throw new CannotAcquireLockLighthouseException(lockKey, e);
		}
		mLockKey = lockKey;
	}

	public void refreshLock() throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		acquireLock(mLockKey);
	}

	public String getLock() {
		return mLockKey;
	}

	public void commit() throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		try {
			final int code = req_put(PATH_LOCK, "", ReqType.REQ_PLAIN);
		} finally {
			mLockKey = null;
		}
	}

	public void rollback() throws LighthouseException {
		if (null == mLockKey) {
			throw new LighthouseException("Lock not acquired");
		}
		req_delete(PATH_LOCK);
	}
}
