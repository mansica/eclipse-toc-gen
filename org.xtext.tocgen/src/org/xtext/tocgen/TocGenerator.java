/*******************************************************************************
 * Copyright (c) 2015 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.xtext.tocgen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class TocGenerator {
	
	public static void main(String[] args) {
		try {
			if (isHelpRequested(args)) {
				System.err.println(
					"Usage: java -jar gen_toc.jar [directory]\n"
					+ "\n"
					+ "The optional argument [directory] must point to a relative or absolute file\n"
					+ "system directory in which the source files are searched. The default is to\n"
					+ "search the current directory. The output is always generated in a subdirectory\n"
					+ "named \"contents\" in the current directory.");
				System.exit(1);
			} else if (args.length == 1) {
				new TocGenerator(args[0], "contents").generate();
			} else {
				new TocGenerator(".", "contents").generate();
			}
		} catch (Throwable t) {
			t.printStackTrace();
			System.exit(1);
		}
	}
	
	private static boolean isHelpRequested(String[] args) {
		if (args.length == 1) {
			return Arrays.asList(new String[] {"h", "-h", "help", "-help", "--help"}).contains(args[0]);
		}
		return args.length > 1;
	}
	
	private final String sourceDirName;
	private final String destDirName;
	private final String fileExtension = ".md";
	private final String alternateFileExtension = ".html";
	private final int maxSectionLevel = 3;
	
	private int indentLevel;
	
	public TocGenerator(String sourceDirName, String destDirName) {
		this.sourceDirName = sourceDirName;
		this.destDirName = destDirName;
	}
	
	public void generate() throws IOException {
		File sourceDir = new File(sourceDirName);
		if (!sourceDir.isDirectory()) {
			System.err.println(sourceDirName + " is not a directory.");
			System.exit(1);
		}
		List<File> sourceFiles = getSourceFiles(sourceDir);
		if (sourceFiles.isEmpty()) {
			System.err.println("The directory " + sourceDirName + " does not contain any valid input files.");
			System.exit(1);
		}
		String docTitle = "";
		File indexFile = new File(sourceDirName + File.separator + "index" + fileExtension);
		if (!indexFile.exists())
			indexFile = new File(sourceDirName + File.separator + "index" + alternateFileExtension);
		if (indexFile.exists())
			docTitle = getVariables(indexFile).get("page.part");
		
		indentLevel = 0;
		File outputFile = new File(destDirName + File.separator + "toc.xml");
		FileWriter output = null;
		try {
			output = new FileWriter(outputFile);
			write(output, "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>");
			write(output, "<toc topic=\"", destDirName, "/index.html\" label=\"", docTitle, "\">");
			indent(+1);
			generateContent(sourceFiles, output);
			indent(-1);
			write(output, "</toc>");
		} finally {
			if (output != null)
				output.close();
		}
		System.out.println("Generated file " + outputFile.getAbsolutePath());
	}
	
	private void generateContent(List<File> markdownFiles, Writer output) throws IOException {
		String lastPart = null;
		for (File file : markdownFiles) {
			String fileName = file.getName().substring(0, file.getName().length() - fileExtension.length());
			Map<String, String> variables = getVariables(file);
			String partName = variables.get("page.part");
			if (!partName.equals(lastPart)) {
				if (lastPart != null) {
					indent(-1);
					write(output, "</topic>");
				}
				write(output, "<topic href=\"", destDirName, "/", fileName, ".html\" label=\"", partName, "\">");
				indent(+1);
			}
			
			FileReader closeable = null;
			try {
				closeable = new FileReader(file);
				BufferedReader reader = new BufferedReader(closeable);
				System.out.println("Processing file " + file.getAbsolutePath());
				generateContent(fileName, reader, output, variables);
			} finally {
				if (closeable != null)
					closeable.close();
			}
			lastPart = partName;
		}
		indent(-1);
		write(output, "</topic>");
	}
	
	private void generateContent(String fileName, BufferedReader reader, Writer output, Map<String, String> variables) throws IOException {
		int lastSectionLevel = 0;
		String line = getNextSection(reader);
		while (line != null) {
			String sectionName = getSectionName(line, variables);
			if (sectionName != null) {
				int sectionLevel = getSectionLevel(line);
				if (lastSectionLevel == 0) {
					write(output, "<topic href=\"", destDirName, "/", fileName, ".html\" label=\"", sectionName, "\">");
					indent(+1);
					lastSectionLevel = 1;
				} else if (sectionLevel <= maxSectionLevel) {
					for (int i = sectionLevel; i <= lastSectionLevel; i++) {
						indent(-1);
						write(output, "</topic>");
					}
					String anchor = getSectionAnchor(line, variables);
					write(output, "<topic href=\"", destDirName, "/", fileName, ".html#", anchor, "\" label=\"", sectionName, "\">");
					indent(+1);
					if (sectionLevel > lastSectionLevel + 1)
						lastSectionLevel = lastSectionLevel + 1;
					else
						lastSectionLevel = sectionLevel;
				}
			}				
			line = getNextSection(reader);
		}
		for (int i = 1; i <= lastSectionLevel; i++) {
			indent(-1);
			write(output, "</topic>");
		}
	}
	
	@SuppressWarnings("resource")
	private Map<String, String> getVariables(File file) throws IOException {
		Map<String, String> result = new HashMap<String, String>();
		FileReader closeable = null;
		try {
			closeable = new FileReader(file);
			BufferedReader reader = new BufferedReader(closeable);
			String line = reader.readLine();
			boolean firstLine = true;
			while (line != null) {
				if (line == null || firstLine && !line.startsWith("---") || !firstLine && line.startsWith("---"))
					return result;
				String[] varDecl = line.split(":");
				if (varDecl.length == 2)
					result.put("page." + varDecl[0].trim(), varDecl[1].trim());
				line = reader.readLine();
				firstLine = false;
			}
		} finally {
			if (closeable != null)
				closeable.close();
		}
		return result;
	}
	
	private String getNextSection(BufferedReader reader) throws IOException {
		String line = reader.readLine();
		while (line != null) {
			if (line.startsWith("#"))
				return line;
			line = reader.readLine();
		}
		return null;
	}
	
	private int getSectionLevel(String line) {
		int result = 0;
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) == '#')
				result++;
			else
				return result;
		}
		return result;
	}
	
	private String getSectionName(String line, Map<String, String> variables) {
		StringBuilder result = new StringBuilder();
		int variableStart = -1;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '{') {
				if (i >= 1 && line.charAt(i - 1) == '{' && variableStart < 0)
					variableStart = i + 1;
			} else if (c == '}') {
				if (i < line.length() - 1 && line.charAt(i + 1) == '}' && variableStart >= 0) {
					String variable = line.substring(variableStart, i);
					result.append(variables.get(variable));
					variableStart = -1;
					i++;
				}
			} else if (c == '#') {
				if (result.length() > 0) {
					// The section anchor is not included in the name
					break;
				}
			} else if (variableStart < 0) {
				result.append(c);
			}
		}
		return result.toString().trim();
	}
	
	private String getSectionAnchor(String line, Map<String, String> variables) {
		int anchorStartIndex = line.indexOf('{');
		int anchorEndIndex = line.indexOf('}');
		if (anchorStartIndex >= 0 && anchorEndIndex > anchorStartIndex) {
			String result = line.substring(anchorStartIndex + 1, anchorEndIndex);
			if (result.startsWith("#"))
				return result.substring(1);
			else
				return result;
		} else {
			String sectionName = getSectionName(line, variables).toLowerCase();
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < sectionName.length(); i++) {
				char c = sectionName.charAt(i);
				if (Character.isLetterOrDigit(c) || c == '-') {
					result.append(c);
				} else if (c == ' ') {
					result.append('-');
				}
			}
			return result.toString();
		}
	}
	
	private Writer write(Writer writer, String... line) throws IOException {
		for (int i = 0; i < indentLevel; i++) {
			writer.write('	');
		}
		for (int j = 0; j < line.length; j++) {
			writer.write(line[j]);
		}
		writer.write('\n');
		return writer;
	}
	
	private void indent(int x) {
		indentLevel += x;
	}
	
	private List<File> getSourceFiles(File sourceDir) {
		File[] filteredFiles = sourceDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isFile() && file.getName().endsWith(fileExtension) && !file.getName().startsWith("index");
			}
		});
		Arrays.sort(filteredFiles, new Comparator<File>() {
			@Override
			public int compare(File file1, File file2) {
				return file1.getName().compareTo(file2.getName());
			}
		});
		return Arrays.asList(filteredFiles);
	}
	
}