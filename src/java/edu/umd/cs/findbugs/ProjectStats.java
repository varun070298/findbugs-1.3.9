/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003, Mike Fagan <mfagan@tde.com>
 * Copyright (C) 2003-2008 University of Maryland
 * Copyright (C) 2008 Google
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.annotation.WillClose;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import edu.umd.cs.findbugs.PackageStats.ClassStats;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.log.Profiler;
import edu.umd.cs.findbugs.workflow.FileBugHash;
import edu.umd.cs.findbugs.xml.OutputStreamXMLOutput;
import edu.umd.cs.findbugs.xml.XMLOutput;
import edu.umd.cs.findbugs.xml.XMLWriteable;

/**
 * Statistics resulting from analyzing a project.
 */
public class ProjectStats implements XMLWriteable, Cloneable {
	private static final String TIMESTAMP_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";
	private static final boolean OMIT_PACKAGE_STATS = SystemProperties.getBoolean("findbugs.packagestats.omit");
	private SortedMap<String, PackageStats> packageStatsMap;
	private int[] totalErrors = new int[] { 0, 0, 0, 0, 0 };
	private int totalClasses;
	private int referencedClasses;
	private int totalSize;
	private int totalSizeFromPackageStats;
	private int totalClassesFromPackageStats;
	private Date analysisTimestamp;
	private Footprint baseFootprint;
	private String java_vm_version = SystemProperties.getProperty("java.vm.version");
	private final Profiler profiler;

	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		buf.append(totalClasses).append(" classes: ");
		for(PackageStats pStats : getPackageStats())
			for(ClassStats cStats : pStats.getSortedClassStats()) 
				buf.append(cStats.getName()).append(" ");
		return buf.toString();
	}
	/**
	 * Constructor. Creates an empty object.
	 */
	public ProjectStats() {
		this.packageStatsMap = new TreeMap<String, PackageStats>();
		this.totalClasses = 0;
		this.analysisTimestamp = new Date();
		this.baseFootprint = new Footprint();
		this.profiler = new Profiler();
	}

	@Override
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			// can't happen
			throw new AssertionError(e);
		}
	}

	public int getCodeSize() {
		if (totalSize > 0)
			return totalSize;
		return totalSizeFromPackageStats;
	}
	public int getTotalBugs() {
		return totalErrors[0];
	}
	public int getBugsOfPriority(int priority) {
		return totalErrors[priority];
	}
	/**
	 * Set the timestamp for this analysis run.
	 *
	 * @param timestamp	 the time of the analysis run this 
	 *                   ProjectStats represents, as previously
	 *                   reported by writeXML.
	 */
	public void setTimestamp(String timestamp) throws ParseException {
		this.analysisTimestamp = new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.ENGLISH).parse(timestamp);
	}

	public void setTimestamp(long timestamp) {
		this.analysisTimestamp = new Date(timestamp);
	}

	public void setVMVersion(String vm_version) {
		this.java_vm_version = vm_version;
	}
	/**
	 * Get the number of classes analyzed.
	 */
	public int getNumClasses() {
		if (totalClasses > 0)
			return totalClasses;
		return totalClassesFromPackageStats;
	}

	/**
     * @return Returns the baseFootprint.
     */
    public Footprint getBaseFootprint() {
	    return baseFootprint;
    }
	
	/**
     * Report that a class has been analyzed.
     *
     * @param className   the full name of the class
     * @param isInterface true if the class is an interface
     * @param size        a normalized class size value;
     *                    see detect/FindBugsSummaryStats.
     * @deprecated Use {@link #addClass(String,String,boolean,int)} instead
     */
	@Deprecated
    public void addClass(String className, boolean isInterface, int size) {
        addClass(className, null, isInterface, size);
    }

	/**
	 * Report that a class has been analyzed.
	 *
	 * @param className   the full name of the class
	 * @param sourceFile TODO
	 * @param isInterface true if the class is an interface
	 * @param size        a normalized class size value;
	 *                    see detect/FindBugsSummaryStats.
	 */
	public void addClass(String className, @CheckForNull String sourceFile, boolean isInterface, int size) {
		String packageName;
		int lastDot = className.lastIndexOf('.');
		if (lastDot < 0)
			packageName = "";
		else
			packageName = className.substring(0, lastDot);
		PackageStats stat = getPackageStats(packageName);
		stat.addClass(className, sourceFile, isInterface, size);
		totalClasses++;
		totalSize += size;
	}
	
	/**
	 * Report that a class has been analyzed.
	 *
	 * @param className   the full name of the class
	 */
	public @CheckForNull ClassStats getClassStats(String className) {
		String packageName;
		int lastDot = className.lastIndexOf('.');
		if (lastDot < 0)
			packageName = "";
		else
			packageName = className.substring(0, lastDot);
		PackageStats stat = getPackageStats(packageName);
		return stat.getClassStatsOrNull(className);
	}

	/**
	 * Called when a bug is reported.
	 */
	public void addBug(BugInstance bug) {
		PackageStats stat = getPackageStats(bug.getPrimaryClass().getPackageName());
		stat.addError(bug);
		++totalErrors[0];
		int priority = bug.getPriority();
		if (priority >= 1) {
			++totalErrors[Math.min(priority, totalErrors.length - 1)];
		}
	}

	/**
	 * Clear bug counts
	 */
	public void clearBugCounts() {
		for(int i = 0; i < totalErrors.length; i++)
			totalErrors[i] = 0;
		for (PackageStats stats : packageStatsMap.values()) {
			stats.clearBugCounts();
		}
	}
	
	public void purgeClassesThatDontMatch(Pattern classPattern) {
		for(Iterator<Map.Entry<String,PackageStats>> i = packageStatsMap.entrySet().iterator(); i.hasNext(); ) {
			Map.Entry<String,PackageStats> e = i.next();
			PackageStats stats = e.getValue();
			stats.purgeClassesThatDontMatch(classPattern);
			if (stats.getClassStats().isEmpty())
				i.remove();
		}
	}
	public void recomputeFromClassStats() {
		for(int i = 0; i < totalErrors.length; i++)
			totalErrors[i] = 0;
		totalSize = 0;
		for (PackageStats stats : packageStatsMap.values()) {
			stats.recomputeFromClassStats();
			totalSize += stats.size();
			for(int i = 0; i < totalErrors.length; i++)
				totalErrors[i] += stats.getBugsAtPriority(i);
		}
		
	}
	FileBugHash fileBugHashes;
	public void computeFileStats(BugCollection bugs) {
		fileBugHashes = FileBugHash.compute(bugs);
	}
	/**
	 * Output as XML.
	 */
	public void writeXML(XMLOutput xmlOutput) throws IOException {
		writeXML(xmlOutput, true);
	}
	/**
	 * Output as XML.
	 */
	public void writeXML(XMLOutput xmlOutput, boolean withMessages) throws IOException {
		xmlOutput.startTag("FindBugsSummary");

		xmlOutput.addAttribute("timestamp",
				new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.ENGLISH).format(analysisTimestamp));
		xmlOutput.addAttribute("total_classes", String.valueOf(totalClasses));
		xmlOutput.addAttribute("referenced_classes", String.valueOf(referencedClasses));
		
		xmlOutput.addAttribute("total_bugs", String.valueOf(totalErrors[0]));
		xmlOutput.addAttribute("total_size", String.valueOf(totalSize));
		xmlOutput.addAttribute("num_packages", String.valueOf(packageStatsMap.size()));

		if (java_vm_version != null)
			xmlOutput.addAttribute("vm_version", java_vm_version);
		Footprint delta = new Footprint(baseFootprint);
		NumberFormat twoPlaces = NumberFormat.getInstance(Locale.ENGLISH);
		twoPlaces.setMinimumFractionDigits(2);
		twoPlaces.setMaximumFractionDigits(2);
		twoPlaces.setGroupingUsed(false);
		long cpuTime = delta.getCpuTime(); // nanoseconds
		if (cpuTime >= 0) {
			xmlOutput.addAttribute("cpu_seconds", twoPlaces.format(cpuTime / 1000000000.0));
		}
		long clockTime = delta.getClockTime(); // milliseconds
		if (clockTime >= 0) {
			xmlOutput.addAttribute("clock_seconds", twoPlaces.format(clockTime / 1000.0));
		}
		long peakMemory = delta.getPeakMemory(); // bytes
		if (peakMemory >= 0) {
			xmlOutput.addAttribute("peak_mbytes", twoPlaces.format(peakMemory / (1024.0*1024)));
		}
		xmlOutput.addAttribute("alloc_mbytes", twoPlaces.format(Runtime.getRuntime().maxMemory() / (1024.0*1024)));
		long gcTime = delta.getCollectionTime(); // milliseconds
		if (gcTime >= 0) {
			xmlOutput.addAttribute("gc_seconds", twoPlaces.format(gcTime / 1000.0));
		}

		PackageStats.writeBugPriorities(xmlOutput, totalErrors);

		xmlOutput.stopTag(false);
		
		if (withMessages && fileBugHashes != null) {
			for(String sourceFile : new TreeSet<String>(fileBugHashes.getSourceFiles())) {
				xmlOutput.startTag("FileStats");
				xmlOutput.addAttribute("path", sourceFile);
				xmlOutput.addAttribute("bugCount", String.valueOf(fileBugHashes.getBugCount(sourceFile)));
				xmlOutput.addAttribute("size", String.valueOf(fileBugHashes.getSize(sourceFile)));
				
				String hash = fileBugHashes.getHash(sourceFile);
				if (hash != null)
					xmlOutput.addAttribute("bugHash", hash);
				xmlOutput.stopTag(true);
				
			}
		}
		
		if (!OMIT_PACKAGE_STATS)
			for (PackageStats stats : packageStatsMap.values()) {
				stats.writeXML(xmlOutput);
			}

		getProfiler().writeXML(xmlOutput);
		xmlOutput.closeTag("FindBugsSummary");
	}

	/**
	 * Report statistics as an XML document to given output stream.
	 */
	public void reportSummary(@WillClose OutputStream out) throws IOException {
		XMLOutput xmlOutput = new OutputStreamXMLOutput(out);
		try {
			writeXML(xmlOutput);
		} finally {
			xmlOutput.finish();
		}
	}

	/**
	 * Transform summary information to HTML.
	 *
	 * @param htmlWriter the Writer to write the HTML output to
	 */
	public void transformSummaryToHTML(Writer htmlWriter)
			throws IOException, TransformerException {

		ByteArrayOutputStream summaryOut = new ByteArrayOutputStream(8096);
		reportSummary(summaryOut);

		StreamSource in = new StreamSource(new ByteArrayInputStream(summaryOut.toByteArray()));
		StreamResult out = new StreamResult(htmlWriter);
		InputStream xslInputStream = this.getClass().getClassLoader().getResourceAsStream("summary.xsl");
		if (xslInputStream == null)
			throw new IOException("Could not load summary stylesheet");
		StreamSource xsl = new StreamSource(xslInputStream);

		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer transformer = tf.newTransformer(xsl);
		transformer.transform(in, out);

		Reader rdr = in.getReader();
		if (rdr != null)
			rdr.close();
		htmlWriter.close();
		InputStream is = xsl.getInputStream();
		if (is != null)
			is.close();
	}

	public Collection<PackageStats> getPackageStats() {
		return packageStatsMap.values();
	}
	private PackageStats getPackageStats(String packageName) {
		PackageStats stat = packageStatsMap.get(packageName);
		if (stat == null) {
			stat = new PackageStats(packageName);
			packageStatsMap.put(packageName, stat);
		}
		return stat;
	}
	public void putIfAbsentPackageStats(String packageName, int numClasses, int size) {
		PackageStats stat = packageStatsMap.get(packageName);
		if (stat == null) {
			stat = new PackageStats(packageName, numClasses, size);
			totalSizeFromPackageStats += size;
			totalClassesFromPackageStats += numClasses;
			packageStatsMap.put(packageName, stat);
			
		}
	}
	/**
	 * @param stats2
	 */
	public void addStats(ProjectStats stats2) {
		totalSize += stats2.getCodeSize();
		totalSizeFromPackageStats += stats2.getCodeSize();
		totalClasses += stats2.getNumClasses();
		totalClassesFromPackageStats += stats2.getNumClasses();
		for(int i = 0; i < totalErrors.length; i++)
			totalErrors[i] += stats2.totalErrors[i];
		
		for (Map.Entry<String,PackageStats> entry : stats2.packageStatsMap.entrySet()) {
			String key = entry.getKey();
			PackageStats pkgStats2 = entry.getValue();
			if (packageStatsMap.containsKey(key)) {
				PackageStats pkgStats = packageStatsMap.get(key);
				for (ClassStats classStats : pkgStats2.getClassStats()) {
					pkgStats.addClass(classStats);
				}
			} else {
				packageStatsMap.put(key, pkgStats2);
			}
		}
	}
	
	/**
     * @param size
     */
    public void setReferencedClasses(int size) {
	    this.referencedClasses = size;
    }
    
    public int getReferencedClasses() {
	    return this.referencedClasses;
    }
    
    /**
     * @return Returns the project profiler instance, never null
     */
    public Profiler getProfiler() {
	    return profiler;
    }
}
