/*
 * MediaWiki import/export processing tools
 * Copyright 2005 by Brion Vibber
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * $Id$
 */

/*
	-> read header info
	site name, url, language, namespace keys
	
	-> read pages.....
	<page>
		-> get title, etc
		<revision>
			-> store each revision
			on next one or end of sequence, write out
			[so for 1.4 schema we can be friendly]
	
	progress report: [TODO]
		if possible, a percentage through file. this might not be possible.
		rates and counts definitely
	
	input:
		stdin or file
		gzip and bzip2 decompression on files with standard extensions
	
	output:
		stdout
		file
		gzip file
		bzip2 file
		future: SQL directly to a server?
	
	output formats:
		XML export format 0.3
		1.4 SQL schema
		1.5 SQL schema
		
*/

package org.mediawiki.dumper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.bzip2.CBZip2InputStream;
import org.apache.commons.compress.bzip2.CBZip2OutputStream;

import org.mediawiki.importer.*;


class Dumper {
	public static void main(String[] args) throws IOException {
		InputStream input = null;
		OutputStream output = null;
		DumpWriter sink = null;
		MultiWriter writers = new MultiWriter();
		
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			String[] bits = splitArg(arg);
			if (bits != null) {
				String opt = bits[0], val = bits[1], param = bits[2];
				if (opt.equals("output")) {
					if (output != null) {
						// Finish constructing the previous output...
						if (sink == null)
							sink = new XmlDumpWriter(output);
						writers.add(sink);
						sink = null;
					}
					output = openOutputFile(val, param);
				} else if (opt.equals("format")) {
					if (output == null)
						output = System.out;
					if (sink != null)
						throw new IllegalArgumentException("Only one format per output allowed.");
					sink = openOutputSink(output, val, param);
				} else if (opt.equals("filter")) {
					if (sink == null) {
						if (output == null)
							output = System.out;
						sink = new XmlDumpWriter(output);
					}
					sink = addFilter(sink, val, param);
				} else {
					throw new IllegalArgumentException("Unrecognized option " + opt);
				}
			} else if (arg.equals("-")) {
				if (input != null)
					throw new IllegalArgumentException("Input already set; can't set to stdin");
				input = System.in;
			} else {
				if (input != null)
					throw new IllegalArgumentException("Input already set; can't set to " + arg);
				input = openInputFile(arg);
			}
		}
		
		if (input == null)
			input = System.in;
		if (output == null)
			output = System.out;
		// Finish stacking the last output sink
		if (sink == null)
			sink = new XmlDumpWriter(output);
		writers.add(sink);
		
		XmlDumpReader reader = new XmlDumpReader(input, writers);
		reader.readDump();
	}
	
	/**
	 * @param arg string in format "--option=value:parameter"
	 * @return array of option, value, and parameter, or null if no match
	 */
	static String[] splitArg(String arg) {
		if (!arg.startsWith("--"))
			return null;
		
		String opt = "";
		String val = "";
		String param = "";
		
		String[] bits = arg.substring(2).split("=", 2);
		opt = bits[0];
		
		if (bits.length > 1) {
			String[] bits2 = bits[1].split(":", 2);
			val = bits2[0];
			if (bits2.length > 1)
				param = bits2[1];
		}
		
		return new String[] {opt, val, param};
	}
	
	static InputStream openInputFile(String arg) throws IOException {
		InputStream infile = new FileInputStream(arg);
		if (arg.endsWith(".gz"))
			return new GZIPInputStream(infile);
		else if (arg.endsWith(".bz2"))
			return openBZip2Stream(infile);
		else
			return infile;
	}

	private static InputStream openBZip2Stream(InputStream infile) throws IOException {
		int first = infile.read();
		int second = infile.read();
		if (first != 'B' || second != 'Z')
			throw new IOException("Didn't find BZ file signature in .bz2 file");
		return new CBZip2InputStream(infile);
	}
	
	static OutputStream openOutputFile(String dest, String param) throws IOException {
		if (dest.equals("stdout"))
			return System.out;
		else if (dest.equals("file"))
			return createOutputFile(param);
		else if (dest.equals("gzip"))
			return new GZIPOutputStream(createOutputFile(param));
		else if (dest.equals("bzip2"))
			return createBZip2File(param);
		else
			throw new IllegalArgumentException("Destination sink not implemented: " + dest);
	}

	private static CBZip2OutputStream createBZip2File(String param) throws IOException, FileNotFoundException {
		OutputStream outfile = createOutputFile(param);
		// bzip2 expects a two-byte 'BZ' signature header
		outfile.write('B');
		outfile.write('Z');
		return new CBZip2OutputStream(outfile);
	}

	private static OutputStream createOutputFile(String param) throws IOException, FileNotFoundException {
		File file = new File(param);
		file.createNewFile();
		return new FileOutputStream(file);
	}
	
	static DumpWriter openOutputSink(OutputStream output, String format, String param) throws IOException {
		if (format.equals("xml"))
			return new XmlDumpWriter(output);
		else if (format.equals("sql")) {
			if (param.equals("1.4"))
				return new SqlWriter14(output);
			else if (param.equals("1.5"))
				return new SqlWriter15(output);
			else
				throw new IllegalArgumentException("SQL version not known: " + param);
		} else
			throw new IllegalArgumentException("Output format not known: " + format);
	}
	
	static DumpWriter addFilter(DumpWriter sink, String filter, String param) throws IOException {
		if (filter.equals("latest"))
			return new LatestFilter(sink);
		else if (filter.equals("namespace"))
			return new NamespaceFilter(sink, param);
		else if (filter.equals("notalk"))
			return new NotalkFilter(sink);
		else if (filter.equals("titlematch"))
			return new TitleMatchFilter(sink, param);
		else if (filter.equals("list"))
			return new ListFilter(sink, param);
		else if (filter.equals("exactlist"))
			return new ExactListFilter(sink, param);
		else
			throw new IllegalArgumentException("Filter unknown: " + filter);
	}
}
