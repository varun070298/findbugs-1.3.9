/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 William Pugh
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

package edu.umd.cs.findbugs.workflow;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.dom4j.DocumentException;

import edu.umd.cs.findbugs.AppVersion;
import edu.umd.cs.findbugs.BugCategory;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.BugRanker;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.ExcludingHashesBugReporter;
import edu.umd.cs.findbugs.I18N;
import edu.umd.cs.findbugs.PackageStats;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.SortedBugCollection;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.PackageStats.ClassStats;
import edu.umd.cs.findbugs.config.CommandLine;
import edu.umd.cs.findbugs.filter.FilterException;
import edu.umd.cs.findbugs.filter.Matcher;

/**
 * Java main application to filter/transform an XML bug collection
 * or bug history collection.
 *
 * @author William Pugh
 */
public class Filter {
	static class FilterCommandLine extends CommandLine {
		/**
		 * 
		 */
		public static final long MILLISECONDS_PER_DAY = 24*60*60*1000L;
		Pattern className,bugPattern;
		public boolean notSpecified = false;
		public boolean not = false;
		long first;
		String firstAsString; 
		long after;
		String afterAsString;  
		long before;
		String beforeAsString; 
		int maxRank = Integer.MAX_VALUE;

		long last;
		String lastAsString; 
		String fixedAsString; // alternate way to specify 'last'
		long present;
		String presentAsString; 
		long absent;
		String absentAsString; 
		String annotation;
		public boolean activeSpecified = false;
		public boolean active = false;

		public boolean hasField = false;
		public boolean hasFieldSpecified = false;

		public boolean hasLocal = false;
		public boolean hasLocalSpecified = false;
		
		public boolean applySuppression = false;
		public boolean applySuppressionSpecified = false;

		public boolean withSource = false;
		public boolean withSourceSpecified = false;
		public boolean knownSource = false;
		public boolean knownSourceSpecified = false;
		public boolean introducedByChange = false;
		public boolean introducedByChangeSpecified = false;

		public boolean removedByChange = false;
		public boolean removedByChangeSpecified = false;

		public boolean newCode = false;
		public boolean newCodeSpecified = false;

		public boolean hashChanged = false;
		public boolean hashChangedSpecified = false;

		public boolean removedCode = false;
		public boolean removedCodeSpecified = false;

		public boolean dontUpdateStats = false;
		public boolean dontUpdateStatsSpecified = false;


		public boolean withMessagesSpecified = false;
		public boolean withMessages = false;
		
		private List<Matcher> includeFilter = new LinkedList<Matcher>();
		private List<Matcher> excludeFilter = new LinkedList<Matcher>();
		HashSet<String> excludedInstanceHashes = new HashSet<String>();
		Set<String> designationKey = new HashSet<String>();
		Set<String> categoryKey = new HashSet<String>();
		
		int priority = 3;

		FilterCommandLine() {

			addSwitch("-not", "reverse (all) switches for the filter");
			addSwitchWithOptionalExtraPart("-knownSource", "trurh", "Only issues that have known source locations");
			addSwitchWithOptionalExtraPart("-withSource", "truth", "only warnings for which source is available");
			addSwitchWithOptionalExtraPart("-hashChanged", "truth", "only warnings for which the stored hash is not the same as the calculated hash");
			addOption("-excludeBugs", "baseline bug collection", "exclude bugs already contained in the baseline bug collection");
			addOption("-exclude", "filter file", "exclude bugs matching given filter");
			addOption("-include", "filter file", "include only bugs matching given filter");

			addOption("-annotation", "text", "allow only warnings containing this text in a user annotation");
			addSwitchWithOptionalExtraPart("-withMessages", "truth", "generated XML should contain textual messages");
			addOption("-after", "when", "allow only warnings that first occurred after this version");
			addOption("-before", "when", "allow only warnings that first occurred before this version");
			addOption("-first", "when", "allow only warnings that first occurred in this version");
			addOption("-last", "when", "allow only warnings that last occurred in this version");
			addOption("-fixed", "when", "allow only warnings that last occurred in the previous version (clobbers last)");
			addOption("-present", "when", "allow only warnings present in this version");
			addOption("-absent", "when", "allow only warnings absent in this version");
			addSwitchWithOptionalExtraPart("-hasField", "truth", "allow only warnings that are annotated with a field");
			addSwitchWithOptionalExtraPart("-hasLocal", "truth", "allow only warnings that are annotated with a local variable");
			addSwitchWithOptionalExtraPart("-active", "truth", "allow only warnings alive in the last sequence number");
			addSwitch("-applySuppression",  "exclude warnings that match the suppression filter");
			
			addSwitchWithOptionalExtraPart("-introducedByChange", "truth",
					"allow only warnings introduced by a change of an existing class");
			addSwitchWithOptionalExtraPart("-removedByChange", "truth",
					"allow only warnings removed by a change of a persisting class");
			addSwitchWithOptionalExtraPart("-newCode", "truth",
			"allow only warnings introduced by the addition of a new class");
			addSwitchWithOptionalExtraPart("-removedCode", "truth",
			"allow only warnings removed by removal of a class");
			addOption("-priority", "level", "allow only warnings with this priority or higher");
			addOption("-maxRank", "rank", "allow only warnings with this rank or lower");
			
			addOption("-class", "pattern", "allow only bugs whose primary class name matches this pattern");
			addOption("-bugPattern", "pattern", "allow only bugs whose type matches this pattern");
			addOption("-category", "category", "allow only warnings with a category that starts with this string");
			addOption("-designation", "designation", "allow only warnings with this designation (e.g., -designation SHOULD_FIX,MUST_FIX)");
			addSwitch("-hashChanged", "recomputed instance hash is different than stored instance hash");
			addSwitch("-dontUpdateStats", "used when withSource is specified to only update bugs, not the class and package stats");
			
		}

		static long getVersionNum(Map<String, AppVersion> versions, 
				SortedMap<Long, AppVersion> timeStamps , String val, 
				boolean roundToLaterVersion, long currentSeqNum) {
			long numVersions = currentSeqNum+1;
			if (val == null) return -1;
			if (val.equals("last") || val.equals("lastVersion")) return numVersions -1;

			AppVersion v = versions.get(val);
			if (v != null) return v.getSequenceNumber();
			try {
				long time = 0;
				if (val.endsWith("daysAgo")) 
					time = System.currentTimeMillis() - MILLISECONDS_PER_DAY * Integer.parseInt(val.substring(0, val.length() - 7));
				else time = Date.parse(val);
				return getAppropriateSeq(timeStamps, time, roundToLaterVersion);
			} catch (Exception e) {
				try {
					long version =  Long.parseLong(val);
					if (version < 0) {
						version = numVersions + version;
					}
					return version;
				}
				catch (NumberFormatException e1) {
					throw new IllegalArgumentException("Could not interpret version specification of '" + val + "'");
				}
			}
		}

		// timeStamps contains 0 10 20 30
		// if roundToLater == true, ..0 = 0, 1..10 = 1, 11..20 = 2, 21..30 = 3, 31.. = Long.MAX
		// if roundToLater == false, ..-1 = Long.MIN, 0..9 = 0, 10..19 = 1, 20..29 = 2, 30..39 = 3, 40 .. = 4
		static private long getAppropriateSeq(SortedMap<Long, AppVersion> timeStamps, long when, boolean roundToLaterVersion) {
			if (roundToLaterVersion) {
				SortedMap<Long, AppVersion> geq = timeStamps.tailMap(when);
				if (geq.isEmpty()) return Long.MAX_VALUE;
				return geq.get(geq.firstKey()).getSequenceNumber();
			} else {
				SortedMap<Long, AppVersion> leq = timeStamps.headMap(when);
				if (leq.isEmpty()) return Long.MIN_VALUE;
				return leq.get(leq.lastKey()).getSequenceNumber();
			}
		}

		edu.umd.cs.findbugs.filter.Filter suppressionFilter;
		void adjustFilter(Project project, BugCollection collection) {
			suppressionFilter = project.getSuppressionFilter();
			Map<String, AppVersion> versions = new HashMap<String, AppVersion>();
			SortedMap<Long, AppVersion> timeStamps = new TreeMap<Long, AppVersion>();

			for(Iterator<AppVersion> i = collection.appVersionIterator(); i.hasNext(); ) {
				AppVersion v = i.next();
				versions.put(v.getReleaseName(), v);
				timeStamps.put(v.getTimestamp(), v);
			}
			// add current version to the maps
			AppVersion v = collection.getCurrentAppVersion();
			versions.put(v.getReleaseName(), v);
			timeStamps.put(v.getTimestamp(), v);

			first = getVersionNum(versions, timeStamps, firstAsString, true, v.getSequenceNumber());
			last = getVersionNum(versions, timeStamps, lastAsString, true,  v.getSequenceNumber());
			before = getVersionNum(versions, timeStamps, beforeAsString, true,  v.getSequenceNumber());
			after = getVersionNum(versions, timeStamps, afterAsString, false,  v.getSequenceNumber());
			present = getVersionNum(versions, timeStamps, presentAsString, true,  v.getSequenceNumber());
			absent = getVersionNum(versions, timeStamps, absentAsString, true,  v.getSequenceNumber());

			long fixed = getVersionNum(versions, timeStamps, fixedAsString, true,  v.getSequenceNumber());
			if (fixed >= 0) last = fixed - 1; // fixed means last on previous sequence (ok if -1)
		}

		boolean accept(BugInstance bug) {
			boolean result = evaluate(bug);
			if (not) return !result;
			return result;
		}
		boolean evaluate(BugInstance bug) {


			for(Matcher m : includeFilter)
				if (!m.match(bug)) return false;
			for(Matcher m : excludeFilter)
				if (m.match(bug)) return false;
			if (excludedInstanceHashes.contains(bug.getInstanceHash())) return false;
			if (annotation != null && bug.getAnnotationText().indexOf(annotation) == -1)
				return false;
			if (bug.getPriority() > priority)
				return false;
			if (firstAsString != null && bug.getFirstVersion() != first)
				return false;
			if (afterAsString != null && bug.getFirstVersion() <= after)
				return false;
			if (beforeAsString != null && bug.getFirstVersion() >= before)
				return false;
			if ((lastAsString != null || fixedAsString != null) && (last < 0 || bug.getLastVersion() != last))
				return false;
			if (presentAsString != null && !bugLiveAt(bug, present))
				return false;
			if (absentAsString != null && bugLiveAt(bug, absent))
				return false;

			if (hasFieldSpecified && (hasField != (bug.getPrimaryField() != null)))
					return false;
			if (hasLocalSpecified && (hasLocal != (bug.getPrimaryLocalVariableAnnotation() != null)))
					return false;

			if (maxRank < Integer.MAX_VALUE &&  BugRanker.findRank(bug) > maxRank)
				return false;

			if (activeSpecified && active == bug.isDead())
				return false;
			if (removedByChangeSpecified
					&& bug.isRemovedByChangeOfPersistingClass() != removedByChange)
				return false;
			if (introducedByChangeSpecified
					&& bug.isIntroducedByChangeOfExistingClass() != introducedByChange)
				return false;
			if (newCodeSpecified && newCode != (!bug.isIntroducedByChangeOfExistingClass() && bug.getFirstVersion() != 0))
				return false;
			if (removedCodeSpecified && removedCode != (!bug.isRemovedByChangeOfPersistingClass() && bug.isDead()))
				return false;

			if (bugPattern != null && !bugPattern.matcher(bug.getType()).find())
					return false;
			if (className != null && !className.matcher(bug.getPrimaryClass().getClassName()).find())
					return false;

			BugPattern thisBugPattern = bug.getBugPattern();
			if (!categoryKey.isEmpty() && thisBugPattern != null && !categoryKey.contains(thisBugPattern.getCategory()))
				return false;
			if (!designationKey.isEmpty() && !designationKey.contains(bug.getUserDesignationKey()))
				return false;

			
			if (hashChangedSpecified) {
				if (bug.isInstanceHashConsistent()  == hashChanged) 
					return false;
			}
			if (applySuppressionSpecified && applySuppression 
					&& suppressionFilter.match(bug))
				return false;
			SourceLineAnnotation primarySourceLineAnnotation = bug.getPrimarySourceLineAnnotation();
			
			if (knownSourceSpecified) {
				if (primarySourceLineAnnotation.isUnknown() == knownSource)
					return false;
			}
			if (withSourceSpecified) {
				if (sourceSearcher.findSource(primarySourceLineAnnotation) != withSource) 
					return false;
			}
			return true;
		}


	    private void addDesignationKey(String argument) {
	    	I18N i18n = I18N.instance();
			
		    for(String x : argument.split("[,|]")) {
				for (String designationKey : i18n.getUserDesignationKeys()) {
					if (designationKey.equals(x) 
							|| i18n.getUserDesignation(designationKey).equals(x)) {
						this.designationKey.add(designationKey);
						break;
					}

				}
			}
	    }
	    private void addCategoryKey(String argument) {
	    	I18N i18n = I18N.instance();
			
		    for(String x : argument.split("[,|]")) {
				for (BugCategory category : i18n.getBugCategoryObjects()) {
					if (category.getAbbrev().equals(x) || category.getCategory().equals(x)) {
						this.categoryKey.add(category.getCategory());
						break;
					}
				}
			}
	    }
		private boolean bugLiveAt(BugInstance bug, long now) {
			if (now < bug.getFirstVersion())
				return false;
			if (bug.isDead() && bug.getLastVersion() < now)
				return false;
			return true;
		}

		@Override
		protected void handleOption(String option, String optionExtraPart) throws IOException {
			option = option.substring(1);
			if (optionExtraPart.length() == 0)
				setField(option, true);
			else
				setField(option, Boolean.parseBoolean(optionExtraPart));
			setField(option+"Specified", true);
		}

		private void setField(String fieldName, boolean value) {
			try {
			Field f = FilterCommandLine.class.getField(fieldName);
			f.setBoolean(this, value);
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}

		}
		@Override
		protected void handleOptionWithArgument(String option, String argument) throws IOException {

			if (option.equals("-priority")) {
				priority = parsePriority(argument);
			}


			else if (option.equals("-maxRank")) 
				maxRank = Integer.parseInt(argument);
			
			else if (option.equals("-first")) 
				firstAsString = argument;
			else if (option.equals("-last")) 
				lastAsString = argument;
			else if (option.equals("-fixed")) 
				fixedAsString = argument;
			else if (option.equals("-after")) 
				afterAsString = argument;
			else if (option.equals("-before")) 
				beforeAsString = argument;
			else if (option.equals("-present")) 
				presentAsString = argument;
			else if (option.equals("-absent")) 
				absentAsString = argument;

			else if (option.equals("-category"))
				addCategoryKey(argument);
			else if (option.equals("-designation"))
				addDesignationKey(argument);
			else if (option.equals("-class"))
					className = Pattern.compile(argument.replace(',', '|'));
			else if (option.equals("-bugPattern"))
					bugPattern = Pattern.compile(argument);
			else if (option.equals("-annotation"))
				annotation = argument;
			else if (option.equals("-excludeBugs")) {
				try {
					ExcludingHashesBugReporter.addToExcludedInstanceHashes(excludedInstanceHashes, argument);
				} catch (DocumentException e) {
					throw new IllegalArgumentException("Error processing include file: " + argument, e);
				}
			} else if (option.equals("-include")) {
				try {
					includeFilter.add(new edu.umd.cs.findbugs.filter.Filter(argument));
				} catch (FilterException e) {
					throw new IllegalArgumentException("Error processing include file: " + argument, e);
				}
			} else if (option.equals("-exclude")) {
				try {
					excludeFilter.add(new edu.umd.cs.findbugs.filter.Filter(argument));
				} catch (FilterException e) {
					throw new IllegalArgumentException("Error processing include file: " + argument, e);
				}
			} else throw new IllegalArgumentException("can't handle command line argument of " + option);
		}



	}
	public static int parsePriority(String argument) {
		int i = " HMLE".indexOf(argument);
		if (i == -1)
			i = " 1234".indexOf(argument);
		if (i == -1)
			throw new IllegalArgumentException("Bad priority: " + argument);
		return i;
	}

	static SourceSearcher sourceSearcher;

	public static void main(String[] args) throws Exception {
		DetectorFactoryCollection.instance();
		
		final FilterCommandLine commandLine = new FilterCommandLine();

		int argCount = commandLine.parse(args, 0, 2, "Usage: " + Filter.class.getName()
				+ " [options] [<orig results> [<new results]] ");
		SortedBugCollection origCollection = new SortedBugCollection();

		if (argCount == args.length)
			origCollection.readXML(System.in);
		else
			origCollection.readXML(args[argCount++]);
		boolean verbose = argCount < args.length;
		SortedBugCollection resultCollection = origCollection.createEmptyCollectionWithMetadata();
		Project project = resultCollection.getProject();
		int passed = 0;
		int dropped = 0;
		resultCollection.setWithMessages(commandLine.withMessages);
		if (commandLine.hashChangedSpecified)
			origCollection.computeBugHashes();
		commandLine.adjustFilter(project, resultCollection);
		resultCollection.getProjectStats().clearBugCounts();
		if (commandLine.className != null) {
			resultCollection.getProjectStats().purgeClassesThatDontMatch(commandLine.className);
		}
		sourceSearcher = new SourceSearcher(project);
		for (BugInstance bug : origCollection.getCollection())
			if (commandLine.accept(bug)) {
				resultCollection.add(bug, false);
				if (!bug.isDead() )
					resultCollection.getProjectStats().addBug(bug);
				passed++;
			} else
				dropped++;

		
		
		if (verbose)
			System.out.println(passed + " warnings passed through, " + dropped
				+ " warnings dropped");
		if (commandLine.withSourceSpecified && commandLine.withSource && !commandLine.dontUpdateStats) {
			for(PackageStats stats : resultCollection.getProjectStats().getPackageStats()) {
				Iterator<ClassStats> i = stats.getClassStats().iterator();
				while (i.hasNext()) {
					String className = i.next().getName();
					if (sourceSearcher.sourceNotFound.contains(className) || !sourceSearcher.sourceFound.contains(className) 
							&& !sourceSearcher.findSource(SourceLineAnnotation.createReallyUnknown(className)
									)) 
						i.remove();
				}
			}
			resultCollection.getProjectStats().recomputeFromClassStats();
		}
		if (argCount == args.length) {
			assert !verbose;
			resultCollection.writeXML(System.out);
		}
		else {
			resultCollection.writeXML(args[argCount++]);

		}

	}


}

// vim:ts=4
