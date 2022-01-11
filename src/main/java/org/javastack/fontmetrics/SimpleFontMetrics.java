/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.javastack.fontmetrics;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// https://github.com/metabolize/anafanafo/blob/master/packages/char-width-table-builder/src/
public class SimpleFontMetrics {
	public static final String FONT_NAME = "Verdana";
	public static final int FONT_SIZE = 110;
	private static final String FILE_RESOURCE_INDEX_CACHE = "/fontmetrics.bin";
	private static final Logger log = LoggerFactory.getLogger(SimpleFontMetrics.class);

	private static final SimpleFontMetrics INSTANCE = new SimpleFontMetrics();
	private FontMetricsHelper metrics = null;

	public static SimpleFontMetrics getInstance() {
		return INSTANCE;
	}

	private SimpleFontMetrics() {
		metrics = SystemFontMetrics.getDefaultInstance();
		if (metrics == null) {
			metrics = IndexedFontMetrics.getDefaultInstance();
		}
	}

	public int widthOf(final String input) {
		return metrics.widthOf(input);
	}

	public byte widthOf(final int codePoint) {
		return metrics.widthOf(codePoint);
	}

	public static final List<List<Integer>> loadRanges(final String name) throws IOException {
		final URL url = SimpleFontMetrics.class.getResource("/ranges." + name + ".txt");
		InputStream is = null;
		URLConnection conn = null;
		try {
			conn = url.openConnection();
			conn.setDoOutput(false);
			conn.setUseCaches(true);
			conn.connect();
			is = conn.getInputStream();
			final int size = conn.getContentLength();
			final byte[] buf = new byte[size];
			is.read(buf);
			return processRawRanges(new String(buf));
		} finally {
			closeSilent(is);
		}
	}

	private static final List<List<Integer>> processRawRanges(final String rawRanges) {
		final Pattern regexp = Pattern.compile("^([^ ]+) . ([^ ]+) {3}");
		final String[] lines = rawRanges.split("\n");
		final ArrayList<List<Integer>> ranges = new ArrayList<List<Integer>>(lines.length);
		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			final Matcher m = regexp.matcher(line);
			if (m.find()) {
				final List<Integer> range = Collections.unmodifiableList(Arrays.asList( //
						Integer.valueOf(m.group(1), 16), //
						Integer.valueOf(m.group(2), 16) //
				));
				ranges.add(range);
			} else {
				throw new RuntimeException("Invalid line: " + line);
			}
		}
		ranges.sort(new Comparator<List<Integer>>() {
			@Override
			public int compare(List<Integer> a, List<Integer> b) {
				return Integer.compare(a.get(0), b.get(0));
			}
		});
		return Collections.unmodifiableList(ranges);
	}

	public interface FontMetricsHelper {
		public int widthOf(final String string);

		public byte widthOf(final int codePoint);
	}

	public static class SystemFontMetrics implements FontMetricsHelper {
		private static SystemFontMetrics INSTANCE = null;
		private final FontMetrics metrics;

		static {
			try {
				INSTANCE = new SystemFontMetrics();
			} catch (Throwable t) {
				log.warn("SystemFontMetrics not available: " + t);
			}
		}

		public static SystemFontMetrics getDefaultInstance() {
			return INSTANCE;
		}

		public SystemFontMetrics() {
			final BufferedImage canvas = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
			final Graphics graphics = canvas.getGraphics();
			final Font font = new Font(FONT_NAME, Font.PLAIN, FONT_SIZE);
			// https://github.com/corretto/corretto-11/issues/118
			this.metrics = graphics.getFontMetrics(font); // NPE AWS-Lambda-Java11
		}

		public int widthOf(final String input) {
			return metrics.stringWidth(input);
		}

		public byte widthOf(final int codePoint) {
			return (byte) Math.min(Math.max(metrics.charWidth(codePoint), 0), 127);
		}

		public static List<String> getFontList() {
			return Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment() //
					.getAvailableFontFamilyNames());
		}

		public void exportFile(final List<List<Integer>> ranges, final String file) throws IOException {
			final List<Byte> widths = computeWidthsOfRanges(ranges);
			final ByteBuffer bb = toByteBuffer(ranges, widths);
			FileOutputStream fos = null;
			FileChannel chan = null;
			try {
				fos = new FileOutputStream(file);
				chan = fos.getChannel();
				chan.write(bb);
			} finally {
				closeSilent(chan);
				closeSilent(fos);
			}
		}

		private final List<Byte> computeWidthsOfRanges(final List<List<Integer>> ranges) {
			final Byte[] widths = new Byte[sumRanges(ranges)];
			int offset = 0;
			for (final List<Integer> r : ranges) {
				final Integer lower = r.get(0);
				final Integer upper = r.get(1);
				widthOfRange(lower.intValue(), upper.intValue(), widths, offset);
				offset += (upper.intValue() - lower.intValue()) + 1;
			}
			return Arrays.asList(widths);
		}

		private final int sumRanges(final List<List<Integer>> ranges) {
			int cx = 0;
			for (final List<Integer> r : ranges) {
				final Integer lower = r.get(0);
				final Integer upper = r.get(1);
				cx += (upper.intValue() - lower.intValue()) + 1;
			}
			return cx;
		}

		private final void widthOfRange(final int lower, final int upper, final Byte[] widths,
				final int offset) {
			for (int codePoint = lower; codePoint <= upper; codePoint++) {
				byte width = 0;
				if (codePoint < 32) {
					width = 0;
				} else {
					width = this.widthOf(codePoint);
				}
				widths[offset + codePoint - lower] = Byte.valueOf(width);
			}
		}

		private final ByteBuffer toByteBuffer(final List<List<Integer>> ranges, final List<Byte> widths) {
			final int rangeSize = (1 + (ranges.size() * 2)) * 4; // Size of 2 Int in bytes + 1-header (int)
			final int widthSize = (1 * 4) + (widths.size() * 1); // Size of Bytes in bytes + 1-header (int)
			final int totalSize = rangeSize + widthSize;
			ByteBuffer bb = ByteBuffer.allocate(totalSize);
			bb.putInt(ranges.size()); // Number of Ranges
			for (final List<Integer> r : ranges) {
				final Integer lower = r.get(0);
				final Integer upper = r.get(1);
				bb.putInt(lower.intValue());
				bb.putInt(upper.intValue());
			}
			bb.putInt(widths.size()); // Number of Widths
			for (final Byte b : widths) {
				bb.put(b.byteValue());
			}
			bb.flip();
			return bb;
		}
	}

	public static class IndexedFontMetrics implements FontMetricsHelper {
		private static IndexedFontMetrics INSTANCE = null;
		private final int[] ranges;
		private final byte[] widths;

		static {
			try {
				INSTANCE = importFile(IndexedFontMetrics.class.getResource(FILE_RESOURCE_INDEX_CACHE));
			} catch (Exception e) {
				log.error("IndexedFontMetrics not available: " + e);
				throw new RuntimeException(e);
			}
		}

		public static IndexedFontMetrics getDefaultInstance() {
			return INSTANCE;
		}

		private IndexedFontMetrics(final int[] ranges, final byte[] widths) {
			this.ranges = ranges;
			this.widths = widths;
		}

		private int findOffsetByRangeScan(final int codePoint) {
			int offset = 0;
			for (int i = 0; i < ranges.length; i += 2) {
				final int lower = ranges[i];
				final int upper = ranges[i + 1];
				if ((codePoint >= lower) && (codePoint <= upper)) {
					return (offset + (codePoint - lower));
				}
				offset += (upper - lower) + 1;
			}
			return -1;
		}

		public int widthOf(final String input) {
			int width = 0;
			final int len = input.length();
			for (int i = 0; i < len; i++) {
				final int codePoint = input.codePointAt(i);
				final int w = widthOf(codePoint);
				width += w;
			}
			return width;
		}

		public byte widthOf(final int codePoint) {
			if (codePoint < 32) {
				return 0;
			}
			final int offset = findOffsetByRangeScan(codePoint);
			if ((offset < 0) || (offset >= widths.length)) {
				return FONT_SIZE;
			}
			return widths[offset];
		}

		public static IndexedFontMetrics importFile(final URL url) throws IOException {
			InputStream is = null;
			URLConnection conn = null;
			try {
				conn = url.openConnection();
				conn.setDoOutput(false);
				conn.setUseCaches(true);
				conn.connect();
				is = conn.getInputStream();
				final int size = conn.getContentLength();
				final byte[] buf = new byte[size];
				is.read(buf);
				return fromByteBuffer(ByteBuffer.wrap(buf));
			} finally {
				closeSilent(is);
			}
		}

		public static IndexedFontMetrics importFile(final File file) throws IOException {
			return importFile(file.toURI().toURL());
		}

		public static IndexedFontMetrics importFile(final String file) throws IOException {
			return importFile(new File(file));
		}

		private static final IndexedFontMetrics fromByteBuffer(final ByteBuffer bb) {
			final int rangeCount = bb.getInt(); // Number of Ranges
			final int[] ranges = new int[rangeCount * 2];
			for (int i = 0, o = 0; i < rangeCount; i++, o += 2) {
				ranges[o] = bb.getInt();
				ranges[o + 1] = bb.getInt();
			}
			final int widthCount = bb.getInt(); // Number of Widths
			final byte[] widths = new byte[widthCount];
			for (int i = 0; i < widthCount; i++) {
				widths[i] = bb.get();
			}
			bb.flip();
			return new IndexedFontMetrics(ranges, widths);
		}
	}

	private static final void closeSilent(final Closeable c) {
		try {
			c.close();
		} catch (Exception ign) {
		}
	}

	/**
	 * Simple test
	 * 
	 * @param args ignored
	 * @throws Throwable if error
	 */
	public static void main(String[] args) throws Throwable {
		long begin;
		//
		begin = System.currentTimeMillis();
		final String TEST_FILE = new File("/tmp/", FILE_RESOURCE_INDEX_CACHE).getAbsolutePath();
		System.out.println("TestFile=" + TEST_FILE);
		final SystemFontMetrics sys = SystemFontMetrics.getDefaultInstance();
		sys.exportFile(loadRanges("short"), TEST_FILE);
		final IndexedFontMetrics idx = IndexedFontMetrics.importFile(TEST_FILE);
		System.out.println("Export/Import Time=" + (System.currentTimeMillis() - begin));

		System.out.println("<LF> ==> " + sys.widthOf('\n') + " => " + idx.widthOf('\n'));
		System.out.println("<SPACE> ==> " + sys.widthOf(' ') + " => " + idx.widthOf(' '));
		System.out.println("A ==> " + sys.widthOf('A') + " => " + idx.widthOf('A'));
		System.out.println("<~> ==> " + sys.widthOf('~') + " => " + idx.widthOf('~'));
		System.out.println("<127> ==> " + sys.widthOf(127) + " => " + idx.widthOf(127));
		System.out.println("<128> ==> " + sys.widthOf(128) + " => " + idx.widthOf(128));
		System.out.println("<NB> ==> " + sys.widthOf(160) + " => " + idx.widthOf(160));
		System.out.println("<SHY> ==> " + sys.widthOf(0xAD) + " => " + idx.widthOf(0xAD));
		System.out.println("<255> ==> " + sys.widthOf(255) + " => " + idx.widthOf(255));
		System.out.println("<7680> ==> " + sys.widthOf(7680) + " => " + idx.widthOf(7680));
		System.out.println("<7821> ==> " + sys.widthOf(7821) + " => " + idx.widthOf(7821));
		System.out.println("<7935> ==> " + sys.widthOf(7935) + " => " + idx.widthOf(7935));

		final String hello = "Hello World!";
		System.out.println(hello + " ==> " + sys.widthOf(hello) + " => " + idx.widthOf(hello));

		// Simple Benchmark
		begin = System.currentTimeMillis();
		for (int i = 0; i < 1e7; i++) {
			sys.widthOf(hello);
		}
		System.out.println("SystemFontMetrics Time=" + (System.currentTimeMillis() - begin));
		//
		begin = System.currentTimeMillis();
		for (int i = 0; i < 1e7; i++) {
			idx.widthOf(hello);
		}
		System.out.println("IndexedFontMetrics Time=" + (System.currentTimeMillis() - begin));
	}
}
