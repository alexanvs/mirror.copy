/**
 * Copyright (c) 2017 Aleksandr Sviridenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package mirror.copy.ver1;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Copier {

	private static final String CHECKSUMS_AND_DATE_AND_SIZE = "checksumsAndDateAndSize";
	private static final String DATE_AND_SIZE = "dateAndSize";
	private static final String CHECKSUMS = "checksums";
	private static final String STEP1 = "step1";
	private static final String STEP2 = "step2";
	private static final String SOURCE = "-source";
	private static final String TARGET = "-target";
	private static final String DEBUG = "-debug";
	private static final String METHOD = "-method";
	private static String mode = null;
	private static String sourceFolderString;
	private static String targetFolderString;

	private static FolderInfo results1 = null; // The state of the first folder in the beginning
	private static FolderInfo results2 = null; // The state of the first folder after some changes

	public static void main(String[] args) throws Exception {
		System.out.println("This program replays file movements of the first directory in the second directory.\n");
		System.out.println("You need to run this program 2 times. First, before folder modification; and specify a source folder.");
		System.out.println("Second, after the rename and specify a target folder.\n");
		System.out.println("Usage:");
		System.out.println("java -jar mirror.copy.ver1.jar step1 -source <path_to_source_folder> [-comparisonStrategy <one_of_methods>]");
		System.out.println("where <one_of_methods> is one of: \"" + CHECKSUMS + "\", \"" + DATE_AND_SIZE + "\" or \"" + CHECKSUMS_AND_DATE_AND_SIZE + "\"");
		System.out.println("...");
		System.out.println("java -jar mirror.copy.ver1.jar step2 -target <path_to_target_folder>\n\n");
		if (args == null || args.length == 0) {
			System.out.println("Please specify arguments.");
			System.exit(1);
		}
		boolean debugOn = false;
		String method = CHECKSUMS; // default
		for (int i = 0; i < args.length; i++) {
			String param = args[i];
			switch (param) {
			case DEBUG: {
				debugOn = true;
				break;
			}
			case STEP1: {
				mode = STEP1;
				break;
			}
			case STEP2: {
				mode = STEP2;
				break;
			}
			case SOURCE: {
				i++;
				if (args.length < i) {
					System.out.println("No source folder specified.");
					System.exit(1);
				}
				sourceFolderString = args[i];
				break;
			}
			case TARGET: {
				i++;
				if (args.length < i) {
					System.out.println("No target folder specified.");
					System.exit(1);
				}
				targetFolderString = args[i];
				break;
			}
			case METHOD: {
				i++;
				if (args.length < i) {
					System.out.println("No method specified.");
					System.exit(1);
				}
				String methodInArgs = args[i];
				if (DATE_AND_SIZE.equals(methodInArgs)) {
					method = DATE_AND_SIZE;
					break;
				}
				if (CHECKSUMS_AND_DATE_AND_SIZE.equals(methodInArgs)) {
					method = CHECKSUMS_AND_DATE_AND_SIZE;
					break;
				}
				if (CHECKSUMS.equals(methodInArgs)) {
					method = CHECKSUMS;
					break;
				}
				System.out.println("Incorrect method specified: " + methodInArgs);
				System.exit(1);
			}
			default: {
				System.out.println("Incorrect argument " + param);
				System.exit(1);
			}
			}
		}
		if (mode == STEP1) {
			if (sourceFolderString == null) {
				System.out.println("No source folder specified for step 1");
				System.exit(1);
			}
			File source = new File(sourceFolderString);
			if (!source.isDirectory()) {
				System.out.println("Source folder is not a directory or does not exist.");
				System.exit(1);
			}
			results1 = new FolderInfo(source, method);

			long timeStart = System.currentTimeMillis();

			doStep1(source, results1, true);
			if (debugOn) {
				System.out.println("[debug output1:]");
				printmap(results1.getIds());
			}
			File savedChecksum = new File("./saved.checksum");
			if (savedChecksum.exists()) {
				savedChecksum.delete();
			}
			savedChecksum.createNewFile();

			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(savedChecksum));
			oos.writeObject(results1);
			oos.close();

			long timeEnd = System.currentTimeMillis();
			System.out.println("Time spent for Step 1: " + (timeEnd - timeStart) + " ms");
			System.out.println("Step 1 is completed. You can now modify this folder and replay your changes running Step 2 afterwards.");
		}

		if (mode == STEP2) {
			if (targetFolderString == null) {
				System.out.println("No target folder specified for step 2");
				System.exit(1);
			}
			File target = new File(targetFolderString);
			if (!target.isDirectory()) {
				System.out.println("Target folder is not a directory or does not exist.");
				System.exit(1);
			}

			File savedChecksum = new File("./saved.checksum");
			if (!savedChecksum.exists()) {
				System.out.println("ERROR: Results of Step 1 do not exist.");
				System.exit(-1);
			}
			// Reading the state of the first folder as it was, from saved file into results1
			ObjectInputStream in = new ObjectInputStream(new FileInputStream(savedChecksum));
			results1 = (FolderInfo) in.readObject();
			in.close();
			if (debugOn) {
				System.out.println("[debug output results1:]");
				printmap(results1.getIds());
			}

			long timeStart = System.currentTimeMillis();
			int returnCode = doStep2(target, debugOn);

			// Saving results2 into a file for further use as results1
			savedChecksum.delete();
			savedChecksum.createNewFile();

			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(savedChecksum));
			oos.writeObject(results2);
			oos.close();

			long timeEnd = System.currentTimeMillis();
			System.out.println("Time spent for Step 2: " + (timeEnd - timeStart) + " ms");
			System.out.println("Step 2 completed.");
			System.exit(returnCode);
		}

	}

	public static byte[] createChecksum(File file) throws Exception {
		InputStream fis = new FileInputStream(file);

		byte[] buffer = new byte[1024];
		MessageDigest complete = MessageDigest.getInstance("MD5");
		int numRead;

		do {
			numRead = fis.read(buffer);
			if (numRead > 0) {
				complete.update(buffer, 0, numRead);
			}
		} while (numRead != -1);

		fis.close();
		return complete.digest();
	}

	public static String getMD5Checksum(File file) throws Exception {
		byte[] b = createChecksum(file);
		String result = "";

		for (int i = 0; i < b.length; i++) {
			result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
		}
		return result;
	}

	private static int doStep1(File sourceFolderFile, FolderInfo results, boolean folderWillBeModified) throws Exception {
		for (File nextChild : sourceFolderFile.listFiles()) {
			if (nextChild.isDirectory()) {
				doStep1(nextChild, results, folderWillBeModified);
			} else {
				Path topLevelFolderPath = results.getFolder().toPath();
				Path childFilePath = nextChild.toPath();
				String relativePath = topLevelFolderPath.relativize(childFilePath).toString();
				Identifier id = null;
				switch (results.getMethod()) {
				case CHECKSUMS: {
					String md5 = getMD5Checksum(nextChild);
					id = new ChecksumIdentifier(md5);
					break;
				}
				case DATE_AND_SIZE: {
					BasicFileAttributes attr = Files.readAttributes(childFilePath, BasicFileAttributes.class);
					id = new DateAndSizeIdentifier(attr.size(), attr.creationTime().toMillis(), attr.lastModifiedTime().toMillis(), attr.lastAccessTime().toMillis());
					break;
				}
				case CHECKSUMS_AND_DATE_AND_SIZE: {
					BasicFileAttributes attr = Files.readAttributes(childFilePath, BasicFileAttributes.class);
					id = new ChecksumAndDateAndSizeIdengifier(nextChild.getAbsolutePath(), attr.size(), attr.creationTime().toMillis(), attr.lastModifiedTime().toMillis(),
							attr.lastAccessTime().toMillis(), folderWillBeModified);
					break;
				}

				}
				results.addId(id, relativePath);
			}
		}
		return 0;
	}

	private static int doStep2(File target, boolean debugOn) throws Exception {
		String method = results1.getMethod();
		System.out.println("Method used in Step 1: " + method);
		results2 = new FolderInfo(results1.getFolder(), method);

		doStep1(results1.getFolder(), results2, false);

		if (debugOn) {
			System.out.println("[debug output results2:]");
			printmap(results2.getIds());
		}
		if (results2.equals(results1)) {
			System.out.println("Nothing changed in source folder " + results1.getFolder().getAbsolutePath());
			return 0;
		}

		if (debugOn) {
			FolderInfo targetState = new FolderInfo(target, method); // The state of the first folder after some changes
			try {
				doStep1(targetState.getFolder(), targetState, false);
			} catch (Exception e) {
				e.printStackTrace();
			}

			if (results2.equals(targetState)) {
				System.out.println("Source and Target folders are equal.");
				return 0;
			}

			if (!results1.equals(targetState)) {
				System.out.println("Please first synchronize Target folder with Source folder, run Step 1,");
				System.out.println("then make changes in source folder, then run Step 2");
				return 1;
			}
		}

		Map<Identifier, List<String>> tableR = new HashMap<>();
		Map<Identifier, List<String>> tableI = new HashMap<>();

		for (Map.Entry<Identifier, List<String>> entry : results1.getIds().entrySet()) {
			Identifier key = entry.getKey();
			List<String> values1 = entry.getValue();
			if (results2.getIds().containsKey(key)) {
				List<String> values2 = results2.getIds().get(key);
				List<String> copy = new ArrayList<>();
				copy.addAll(values1);
				copy.removeAll(values2);
				if (!copy.isEmpty()) {
					tableR.put(key, copy);
				}
			} else {
				tableR.put(key, values1);
			}
		}

		for (Map.Entry<Identifier, List<String>> entry : results2.getIds().entrySet()) {
			Identifier key = entry.getKey();
			List<String> values2 = entry.getValue();
			if (results1.getIds().containsKey(key)) {
				List<String> values1 = results1.getIds().get(key);
				List<String> copy = new ArrayList<>();
				copy.addAll(values2);
				copy.removeAll(values1);
				if (!copy.isEmpty()) {
					tableI.put(key, copy);
				}
			} else {
				tableI.put(key, values2);
			}
		}
		if (debugOn) {
			System.out.println("\n[debug output tableR:]");
			printmap(tableR);
			System.out.println("[debug output tableI:]");
			printmap(tableI);
		}
		Iterator<Identifier> it1 = tableR.keySet().iterator();

		while (it1.hasNext()) {
			Identifier s = it1.next();

			if (tableI.keySet().contains(s)) {

				int minNlocs = (tableR.get(s).size() < tableI.get(s).size() ? tableR.get(s).size() : tableI.get(s).size());

				if (tableR.get(s).size() > tableI.get(s).size()) {
					for (int i = minNlocs; i < tableR.get(s).size(); i++) {
						File fold = (new File(target.getAbsolutePath(), tableR.get(s).get(i))).getParentFile();
						deleteFileFrom(target.getAbsolutePath(), tableR.get(s).get(i));
						if (fold.isDirectory()) {
							if (!fold.getAbsolutePath().equals(target.getAbsolutePath())) {
								if (fold.delete()) {
									System.out.println("Folder deleted");
								}
							}
						}
					}
				} else if (tableR.get(s).size() < tableI.get(s).size()) {
					for (int i = minNlocs; i < tableI.get(s).size(); i++) {

						copyFileFromTo(target.getAbsolutePath(), tableR.get(s).get(0), target.getAbsolutePath(), tableI.get(s).get(i));
					}
				}

				for (int i = 0; i < minNlocs; i++) {
					File fold = (new File(target.getAbsolutePath(), tableR.get(s).get(i))).getParentFile();
					moveFileFromTo(target.getAbsolutePath(), tableR.get(s).get(i), target.getAbsolutePath(), tableI.get(s).get(i));
					if (fold.isDirectory()) {
						if (!fold.getAbsolutePath().equals(target.getAbsolutePath())) {
							if (fold.delete()) {
								System.out.println("Folder deleted");
							}
						}
					}
				}
				it1.remove();
				tableI.remove(s);
			} else {
				// {remove file s from Target folder from locations tableR.get(s);
				// remove this s record from tableR: it1.remove();}
				for (int i = 0; i < tableR.get(s).size(); i++) {
					File fold = (new File(target.getAbsolutePath(), tableR.get(s).get(i))).getParentFile();
					deleteFileFrom(target.getAbsolutePath(), tableR.get(s).get(i));
					if (fold.isDirectory()) {
						if (!fold.getAbsolutePath().equals(target.getAbsolutePath())) {
							if (fold.delete()) {
								System.out.println("Folder deleted");
							}
						}
					}
				}
				it1.remove();
			}

		}

		Iterator<Identifier> it2 = tableI.keySet().iterator();

		while (it2.hasNext()) {
			Identifier s = it2.next();
			for (int i = 0; i < tableI.get(s).size(); i++) {
				copyFileFromTo(results1.getFolder().getAbsolutePath(), tableI.get(s).get(i), target.getAbsolutePath(), tableI.get(s).get(i));
			}
		}
		return 0;
	}

	private static void moveFileFromTo(String fromFolder, String loc1, String toFolder, String loc2) {
		File moveWhat = new File(fromFolder, loc1);
		File moveWhere = new File(toFolder, loc2);
		System.out.println("Move " + moveWhat.getAbsolutePath() + " to " + moveWhere.getAbsolutePath());
		moveWhere.getParentFile().mkdirs();
		moveWhat.renameTo(moveWhere);
	}

	private static void deleteFileFrom(String fromFolder, String location) {
		File file = new File(fromFolder, location);
		System.out.println("Delete " + file.getAbsolutePath());
		if (file.exists()) {
			file.delete();
		} else {
			System.out.println("Error deleting " + file.getAbsolutePath());
		}
	}

	private static void copyFileFromTo(String fromFolder, String loc1, String toFolder, String loc2) {
		File copyFrom = new File(fromFolder, loc1);
		File copyTo = new File(toFolder, loc2);
		System.out.println("Copy " + copyFrom.getAbsolutePath() + " to " + copyTo.getAbsolutePath());
		try {
			copyTo.getParentFile().mkdirs();
			Files.copy(copyFrom.toPath(), copyTo.toPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void printmap(Map<Identifier, List<String>> map) {
		for (Entry<Identifier, List<String>> q : map.entrySet()) {
			System.out.println(q.getKey() + " " + q.getValue());
		}
	}

	public static boolean arraysMatch(List<String> pathsForFileStep1, List<String> pathsForFileStep2) {
		if (pathsForFileStep1.size() != pathsForFileStep2.size()) {
			return false;
		}
		List<String> work = new ArrayList<String>(pathsForFileStep2);
		for (String element : pathsForFileStep1) {
			if (!work.remove(element)) {
				return false;
			}
		}
		return work.isEmpty();
	}

	private static interface Identifier {
		boolean equals(Object obj);
	}

	private static class ChecksumIdentifier implements Identifier, Serializable {
		private static final long serialVersionUID = 4096973732600424847L;
		private String checksum;

		public ChecksumIdentifier(String checksum) {
			this.checksum = checksum;
		}

		public String getChecksum() {
			return checksum;
		}

		@Override
		public String toString() {
			return checksum;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof ChecksumIdentifier) {
				ChecksumIdentifier other = (ChecksumIdentifier) obj;
				return this.getChecksum().equals(other.getChecksum());
			}
			return super.equals(obj);
		}

		@Override
		public int hashCode() {
			return checksum.hashCode();
		}
	}

	private static class DateAndSizeIdentifier implements Identifier, Serializable {
		private static final long serialVersionUID = 1556713477972745796L;
		private long size = 0;
		private long creationTime = 0;
		private long modificationTime = 0;
		private long accessTime = 0;

		public DateAndSizeIdentifier(long size, long creationTime, long modifiedTime, long accessTime) {
			this.size = size;
			this.creationTime = creationTime;
			this.modificationTime = modifiedTime;
			this.accessTime = accessTime;
		}

		public String toString() {
			return size + "|" + creationTime + "|" + modificationTime + "|" + accessTime;
		}

		public boolean equals(Object obj) {
			if (obj instanceof DateAndSizeIdentifier) {
				DateAndSizeIdentifier other = (DateAndSizeIdentifier) obj;
				if (size != other.size) {
					return false;
				}
				if (modificationTime != other.modificationTime) {
					return false;
				}
				return true;
			}
			return false;
		}

		@Override
		public int hashCode() {
			return (int) (size + modificationTime);
		}
	}

	private static class ChecksumAndDateAndSizeIdengifier implements Identifier, Serializable {
		private static final long serialVersionUID = -6847482771117005550L;
		private String absolutePath;
		private long size = 0;
		private long creationTime = 0;
		private long modificationTime = 0;
		private long accessTime = 0;
		private boolean forceChecksumCalculation = false;
		private String md5 = null;

		public String calculateChecksum() {
			if (md5 == null) {
				File file = new File(absolutePath);
				try {
					md5 = Copier.getMD5Checksum(file);
				} catch (Exception e) {
					System.out.println("Error during checksum calculation for " + absolutePath);
				}
			}
			return md5;
		}

		public ChecksumAndDateAndSizeIdengifier(String absolutePath, long size, long creationTime, long modifiedTime, long accessTime, boolean forceChecksumCalculation) {
			this.absolutePath = absolutePath;
			this.size = size;
			this.creationTime = creationTime;
			this.modificationTime = modifiedTime;
			this.accessTime = accessTime;
			if (forceChecksumCalculation) {
				try {
					File file = new File(absolutePath);
					this.md5 = Copier.getMD5Checksum(file);
				} catch (Exception e) {
					System.out.println("Error during checksum calculation for " + absolutePath);
				}
			}
		}

		public String toString() {
			return absolutePath + "|" + size + "|" + modificationTime + (md5 == null ? "" : "|" + md5);
		}

		public boolean equals(Object obj) {
			if (obj instanceof ChecksumAndDateAndSizeIdengifier) {
				ChecksumAndDateAndSizeIdengifier other = (ChecksumAndDateAndSizeIdengifier) obj;
				if (size != other.size) {
					return false;
				}
				if (modificationTime != other.modificationTime) {
					return false;
				}
				String calculatedChecksum = this.calculateChecksum();
				if (calculatedChecksum == null || !calculatedChecksum.equals(other.calculateChecksum())) {
					return false;
				}
				return true;
			}
			return false;
		}

		public int hashCode() {
			return (int) (size + modificationTime);
		}
	}

	private static class FolderInfo implements Serializable {
		private static final long serialVersionUID = -4900864651526989655L;
		private String method = null;
		private File folder = null;
		private Map<Identifier, List<String>> idsToLocs = new HashMap<>();

		public FolderInfo(File folder, String method) {
			this.method = method;
			if (!folder.isDirectory()) {
				throw new IllegalArgumentException("Folder " + folder.getAbsolutePath() + " is not a directory.");
			}
			this.folder = folder;
		}

		public File getFolder() {
			return folder;
		}

		public Map<Identifier, List<String>> getIds() {
			return idsToLocs;
		}

		public void addId(Identifier id, String relativePath) {
			idsToLocs.putIfAbsent(id, new ArrayList<String>());
			idsToLocs.get(id).add(relativePath);
		}

		public List<String> getRelativePaths(Identifier id) {
			return idsToLocs.get(id);
		}

		public String getMethod() {
			return this.method;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof FolderInfo) {
				FolderInfo other = (FolderInfo) obj;

				if (getIds().size() != other.getIds().size()) {
					return false;
				}
				for (Map.Entry<Identifier, List<String>> entry : idsToLocs.entrySet()) {

					Identifier ident = entry.getKey();
					List<String> pathsForIdent = entry.getValue();

					List<String> pathsForIdentInOther = other.getRelativePaths(ident);

					if (pathsForIdentInOther == null || !arraysMatch(pathsForIdent, pathsForIdentInOther)) {
						return false;
					}

				}
				return true;
			}
			return false;
		}

		@Override
		public int hashCode() {
			return getIds().hashCode();
		}

	}

}
