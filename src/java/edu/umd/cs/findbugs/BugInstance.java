/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 University of Maryland
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

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.StringTokenizer;

import javax.annotation.Nonnull;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.Type;
import org.objectweb.asm.tree.ClassNode;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.Hierarchy2;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.OpcodeStackScanner;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.bcp.FieldVariable;
import edu.umd.cs.findbugs.ba.vna.ValueNumber;
import edu.umd.cs.findbugs.ba.vna.ValueNumberFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumberSourceInfo;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.FieldDescriptor;
import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.classfile.IAnalysisCache;
import edu.umd.cs.findbugs.classfile.MethodDescriptor;
import edu.umd.cs.findbugs.cloud.Cloud;
import edu.umd.cs.findbugs.internalAnnotations.DottedClassName;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.util.Util;
import edu.umd.cs.findbugs.visitclass.DismantleBytecode;
import edu.umd.cs.findbugs.visitclass.PreorderVisitor;
import edu.umd.cs.findbugs.xml.XMLAttributeList;
import edu.umd.cs.findbugs.xml.XMLOutput;

/**
 * An instance of a bug pattern.
 * A BugInstance consists of several parts:
 * <p/>
 * <ul>
 * <li> the type, which is a string indicating what kind of bug it is;
 * used as a key for the FindBugsMessages resource bundle
 * <li> the priority; how likely this instance is to actually be a bug
 * <li> a list of <em>annotations</em>
 * </ul>
 * <p/>
 * The annotations describe classes, methods, fields, source locations,
 * and other relevant context information about the bug instance.
 * Every BugInstance must have at least one ClassAnnotation, which
 * describes the class in which the instance was found.  This is the
 * "primary class annotation".
 * <p/>
 * <p> BugInstance objects are built up by calling a string of <code>add</code>
 * methods.  (These methods all "return this", so they can be chained).
 * Some of the add methods are specialized to get information automatically from
 * a BetterVisitor or DismantleBytecode object.
 *
 * @author David Hovemeyer
 * @see BugAnnotation
 */
public class BugInstance implements Comparable<BugInstance>, XMLWriteableWithMessages, Serializable, Cloneable {
	private static final long serialVersionUID = 1L;

	private final String type;
	private int priority;
	private ArrayList<BugAnnotation> annotationList;
	private int cachedHashCode;
	private @CheckForNull  BugDesignation userDesignation;
	private BugProperty propertyListHead, propertyListTail;
	private String oldInstanceHash;
	private String instanceHash;
	private int instanceOccurrenceNum;
	private int instanceOccurrenceMax;
	private DetectorFactory detectorFactory;


	/*
	 * The following fields are used for tracking Bug instances across multiple versions of software.
	 * They are meaningless in a BugCollection for just one version of software. 
	 */
	private long firstVersion = 0;
	private long lastVersion = -1;
	private boolean introducedByChangeOfExistingClass;
	private boolean removedByChangeOfPersistingClass;

	/**
	 * This value is used to indicate that the cached hashcode
	 * is invalid, and should be recomputed.
	 */
	private static final int INVALID_HASH_CODE = 0;

	/**
	 * This value is used to indicate whether BugInstances should be reprioritized very low,
	 * when the BugPattern is marked as experimental
	 */
	private static boolean adjustExperimental = false;

	private static Set<String> missingBugTypes = Collections.synchronizedSet(new HashSet<String>());
	
	/**
	 * Constructor.
	 *
	 * @param type     the bug type
	 * @param priority the bug priority
	 */
	public BugInstance(String type, int priority) {
		this.type = type.intern();
		this.priority = priority;
		annotationList = new ArrayList<BugAnnotation>(4);
		cachedHashCode = INVALID_HASH_CODE;

		BugPattern p = I18N.instance().lookupBugPattern(type);
		if (p == null) {
			if (missingBugTypes.add(type)) {
				String msg = "Can't find definition of bug type " + type;
				AnalysisContext.logError(msg, new IllegalArgumentException(msg));
			}
		} else
			this.priority += p.getPriorityAdjustment();
		if (adjustExperimental && isExperimental())
			this.priority = Detector.EXP_PRIORITY;
		boundPriority();
	}

	private void boundPriority() {
		priority = boundedPriority(priority);
	}

	@Override
	public Object clone() {
		BugInstance dup;

		try {
			dup = (BugInstance) super.clone();

			// Do deep copying of mutable objects
			for (int i = 0; i < dup.annotationList.size(); ++i) {
				dup.annotationList.set(i, (BugAnnotation) dup.annotationList.get(i).clone());
			}
			 dup.propertyListHead = dup.propertyListTail = null;
			for (Iterator<BugProperty> i = propertyIterator(); i.hasNext(); ) {
				dup.addProperty((BugProperty) i.next().clone());
			}

			return dup;
		} catch (CloneNotSupportedException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Create a new BugInstance.
	 * This is the constructor that should be used by Detectors.
	 * 
	 * @param detector the Detector that is reporting the BugInstance
	 * @param type     the bug type
	 * @param priority the bug priority
	 */
	public BugInstance(Detector detector, String type, int priority) {
		this(type, priority);
		if (detector != null) {
			// Adjust priority if required
			detectorFactory =
				DetectorFactoryCollection.instance().getFactoryByClassName(detector.getClass().getName());
			if (detectorFactory != null) {
				this.priority += detectorFactory.getPriorityAdjustment();
				boundPriority();
			}
		}

	}

	/**
	 * Create a new BugInstance.
	 * This is the constructor that should be used by Detectors.
	 * 
	 * @param detector the Detector2 that is reporting the BugInstance
	 * @param type     the bug type
	 * @param priority the bug priority
	 */
	public BugInstance(Detector2 detector, String type, int priority) {
		this(type, priority);
		
		if (detector != null) {
			// Adjust priority if required
			detectorFactory =
				DetectorFactoryCollection.instance().getFactoryByClassName(detector.getDetectorClassName());
			if (detectorFactory != null) {
				this.priority += detectorFactory.getPriorityAdjustment();
				boundPriority();
			}
		}

	}
	public static void setAdjustExperimental(boolean adjust) {
		adjustExperimental = adjust;
	}

	/* ----------------------------------------------------------------------
	 * Accessors
	 * ---------------------------------------------------------------------- */

	/**
	 * Get the bug type.
	 */
	public String getType() {
		return type;
	}

	/**
	 * Get the BugPattern.
	 */
	public @NonNull BugPattern getBugPattern() {
		BugPattern result =  I18N.instance().lookupBugPattern(getType());
		if (result != null) return result;
		AnalysisContext.logError("Unable to find description of bug pattern " + getType());
		result = I18N.instance().lookupBugPattern("UNKNOWN");
		if (result != null) return result;
		return BugPattern.REALLY_UNKNOWN;
	}

	/**
	 * Get the bug priority.
	 */
	public int getPriority() {
		return priority;
	}

	
	public int getBugRank() {
		return BugRanker.findRank(this);
	}
	/**
	 * Get a string describing the bug priority and type.
	 * e.g. "High Priority Correctness"
	 * @return a string describing the bug priority and type
	 */
	public String getPriorityTypeString()
	{
		String priorityString = getPriorityString();
		BugPattern bugPattern = this.getBugPattern();
		//then get the category and put everything together
		String categoryString;
		if (bugPattern == null) categoryString = "Unknown category for " + getType();
		else categoryString = I18N.instance().getBugCategoryDescription(bugPattern.getCategory());
		return priorityString + " Priority " + categoryString;
		//TODO: internationalize the word "Priority"
	}

	public String getPriorityTypeAbbreviation()
	{
		String priorityString = getPriorityAbbreviation();
		return priorityString + " " + getCategoryAbbrev();
	}

	public String getCategoryAbbrev() {
	    BugPattern bugPattern = getBugPattern();
	    if (bugPattern == null) return "?";
		return bugPattern.getCategoryAbbrev();
    }

	public String getPriorityString() {
		//first, get the priority
		int value = this.getPriority();
		String priorityString;
		if (value == Detector.HIGH_PRIORITY)
			priorityString = edu.umd.cs.findbugs.L10N.getLocalString("sort.priority_high", "High");
		else if (value == Detector.NORMAL_PRIORITY)
			priorityString = edu.umd.cs.findbugs.L10N.getLocalString("sort.priority_normal", "Medium");
		else if (value == Detector.LOW_PRIORITY)
			priorityString = edu.umd.cs.findbugs.L10N.getLocalString("sort.priority_low", "Low");
		else if (value == Detector.EXP_PRIORITY)
			priorityString = edu.umd.cs.findbugs.L10N.getLocalString("sort.priority_experimental", "Experimental");
		else
			priorityString = edu.umd.cs.findbugs.L10N.getLocalString("sort.priority_ignore", "Ignore"); // This probably shouldn't ever happen, but what the hell, let's be complete
		return priorityString;
	}

	public String getPriorityAbbreviation() {
		return getPriorityString().substring(0,1);
	}
	/**
	 * Set the bug priority.
	 */
	public void setPriority(int p) {
		priority = boundedPriority(p);
	}

	private int boundedPriority(int p) {
		return Math.max(Detector.HIGH_PRIORITY, Math.min(Detector.IGNORE_PRIORITY, p));
	}
	public void raisePriority() {
		priority = boundedPriority(priority-1);

	}
	public void lowerPriority() {
		priority = boundedPriority(priority+1);
	}

	public void lowerPriorityALot() {
		priority = boundedPriority(priority+2);
	}

	/**
	 * Is this bug instance the result of an experimental detector?
	 */
	public boolean isExperimental() {
		BugPattern pattern = getBugPattern();
		return (pattern != null) && pattern.isExperimental();
	}

	/**
	 * Get the primary class annotation, which indicates where the bug occurs.
	 */
	public ClassAnnotation getPrimaryClass() {
		ClassAnnotation result =   findPrimaryAnnotationOfType(ClassAnnotation.class);
		if (result == null) {
			System.out.println("huh");
			result =   findPrimaryAnnotationOfType(ClassAnnotation.class);
		}
		return result;
	}

	/**
	 * Get the primary method annotation, which indicates where the bug occurs.
	 */
	public MethodAnnotation getPrimaryMethod() {
		return  findPrimaryAnnotationOfType(MethodAnnotation.class);
	}
	/**
	 * Get the primary method annotation, which indicates where the bug occurs.
	 */
	public FieldAnnotation getPrimaryField() {
		return findPrimaryAnnotationOfType(FieldAnnotation.class);
	}


	public BugInstance lowerPriorityIfDeprecated() {
		MethodAnnotation m = getPrimaryMethod();
		if (m != null && XFactory.createXMethod(m).isDeprecated())
				lowerPriority();
		FieldAnnotation f = getPrimaryField();
		if (f != null && XFactory.createXField(f).isDeprecated())
			lowerPriority();
		return this;
	}
	/**
	 * Find the first BugAnnotation in the list of annotations
	 * that is the same type or a subtype as the given Class parameter.
	 * 
	 * @param cls the Class parameter
	 * @return the first matching BugAnnotation of the given type,
	 *         or null if there is no such BugAnnotation
	 */
	private <T extends BugAnnotation> T findPrimaryAnnotationOfType(Class<T>  cls) {
		for (Iterator<BugAnnotation> i = annotationIterator(); i.hasNext();) {
			BugAnnotation annotation = i.next();
			if (annotation.getDescription().endsWith("DEFAULT") && cls.isAssignableFrom(annotation.getClass()))
				return cls.cast(annotation);
		}
		return null;
	}

	public LocalVariableAnnotation getPrimaryLocalVariableAnnotation() {
		for (BugAnnotation annotation : annotationList) 
			if (annotation instanceof LocalVariableAnnotation)
				return (LocalVariableAnnotation) annotation;
		return null;
	}
	/**
	 * Get the primary source line annotation.
	 * There is guaranteed to be one (unless some Detector constructed
	 * an invalid BugInstance).
	 *
	 * @return the source line annotation
	 */
	public SourceLineAnnotation getPrimarySourceLineAnnotation() {
		// Highest priority: return the first top level source line annotation
		for (BugAnnotation annotation : annotationList) {
			if (annotation instanceof SourceLineAnnotation
					&& annotation.getDescription().equals(SourceLineAnnotation.DEFAULT_ROLE))
				return (SourceLineAnnotation) annotation;
		}
		for (BugAnnotation annotation : annotationList) {
			if (annotation instanceof SourceLineAnnotation)
				return (SourceLineAnnotation) annotation;
		}

		// Next: Try primary method, primary field, primary class
		SourceLineAnnotation srcLine;
		if ((srcLine = inspectPackageMemberSourceLines(getPrimaryMethod())) != null)
			return srcLine;
		if ((srcLine = inspectPackageMemberSourceLines(getPrimaryField())) != null)
			return srcLine;
		if ((srcLine = inspectPackageMemberSourceLines(getPrimaryClass())) != null)
			return srcLine;

		// Last resort: throw exception
		throw new IllegalStateException("BugInstance must contain at least one class, method, or field annotation");
	}

	static final boolean CHECK = false;
	static int keyCount = 0;
	static int keyDifferentCount = 0;
	static {
		if (CHECK) Util.runLogAtShutdown(new Runnable() {

			public void run() {
	           System.out.printf("%d/%d instance keys changed\n", keyDifferentCount, keyCount);  
            }
			
		});
	}
	public String getInstanceKey() {
		String newValue = getInstanceKeyNew();
		if (!CHECK) return newValue;
		String oldValue = getInstanceKeyOld();
		
		keyCount++;
		if (!oldValue.equals(newValue)) {
			keyDifferentCount++;
			System.out.println(keyDifferentCount);
			System.out.println(getMessageWithoutPrefix());
			System.out.println(oldValue);
			System.out.println(newValue);

		}
		return newValue;
	}
	private String getInstanceKeyOld() {
		StringBuilder buf = new StringBuilder(type);
		for (BugAnnotation annotation : annotationList) {
			if (annotation instanceof SourceLineAnnotation || annotation instanceof MethodAnnotation && !annotation.isSignificant()) {
				// do nothing
			} else {
				buf.append(":");
				buf.append(annotation.format("hash", null));
			}
		}
		return buf.toString();
	}
	private String getInstanceKeyNew() {
		StringBuilder buf = new StringBuilder(type);
		for (BugAnnotation annotation : annotationList) 
			if (annotation.isSignificant() || annotation instanceof IntAnnotation || annotation instanceof LocalVariableAnnotation) {
				buf.append(":");
				buf.append(annotation.format("hash", null));
			}
		
		return buf.toString();
	}
	/**
	 * If given PackageMemberAnnotation is non-null, return its
	 * SourceLineAnnotation.
	 * 
	 * @param packageMember
	 *            a PackageMemberAnnotation
	 * @return the PackageMemberAnnotation's SourceLineAnnotation, or null if
	 *         there is no SourceLineAnnotation
	 */
	private SourceLineAnnotation inspectPackageMemberSourceLines(PackageMemberAnnotation packageMember) {
		return (packageMember != null) ? packageMember.getSourceLines() : null;
	}

	/**
	 * Get an Iterator over all bug annotations.
	 */
	public Iterator<BugAnnotation> annotationIterator() {
		return annotationList.iterator();
	}
	
	/**
	 * Get an Iterator over all bug annotations.
	 */
	public List<? extends BugAnnotation> getAnnotations() {
		return annotationList;
	}

	/**
	 * Get the abbreviation of this bug instance's BugPattern.
	 * This is the same abbreviation used by the BugCode which
	 * the BugPattern is a particular species of.
	 */
	public String getAbbrev() {
		BugPattern pattern = getBugPattern();
		return pattern != null ? pattern.getAbbrev() : "<unknown bug pattern>";
	}

	/** set the user designation object. This will clobber any
	 *  existing annotationText (or any other BugDesignation field). */
	@Deprecated
	public void setUserDesignation(BugDesignation bd) {
		userDesignation = bd;
	}
	/** return the user designation object, which may be null.
	 * 
	 *  A previous calls to getSafeUserDesignation(), setAnnotationText(),
	 *  or setUserDesignation() will ensure it will be non-null
	 *  [barring an intervening setUserDesignation(null)].
	 *  @see #getNonnullUserDesignation() */
	@Deprecated
	@Nullable public BugDesignation getUserDesignation() {
		return userDesignation;
	}
	/** return the user designation object, creating one if
	 *  necessary. So calling
	 *  <code>getSafeUserDesignation().setDesignation("HARMLESS")</code>
	 *  will always work without the possibility of a NullPointerException.
	 *  @see #getUserDesignation() */
	@Deprecated
	@NonNull public BugDesignation getNonnullUserDesignation() {
		if (userDesignation == null)
			userDesignation = new BugDesignation();
		return userDesignation;
	}


	/** Get the user designation key.
	 *  E.g., "MOSTLY_HARMLESS", "CRITICAL", "NOT_A_BUG", etc.
	 *
	 *  If the user designation object is null,returns UNCLASSIFIED.
	 *
	 *  To set the user designation key, call
	 *  <code>getSafeUserDesignation().setDesignation("HARMLESS")</code>.
	 * 
	 *  @see I18N#getUserDesignation(String key)
	 *  @return the user designation key
	 */
	@NonNull public String getUserDesignationKey() {
		if (userDesignation == null) return BugDesignation.UNCLASSIFIED;
		return userDesignation.getDesignationKey();
	}
	
	public @CheckForNull String getUserName() {
		if (userDesignation == null) return null;
		return userDesignation.getUser();
	}
	public  long getUserTimestamp() {
		if (userDesignation == null) return Long.MAX_VALUE;
		return userDesignation.getTimestamp();
	}
	
	@NonNull public int getUserDesignationKeyIndex() {
		return I18N.instance().getUserDesignationKeys().indexOf(getUserDesignationKey());
	}

	/**
	 * @param key
	 * @param bugCollection TODO
	 */
	public void setUserDesignationKey(String key, @CheckForNull BugCollection bugCollection) {
		BugDesignation userDesignation = getNonnullUserDesignation();
		if (userDesignation.getDesignationKey().equals(key))
			return;
		userDesignation.setDesignationKey(key);
		Cloud plugin = bugCollection != null? bugCollection.getCloud() : null;
		if (plugin != null)
			plugin.storeUserAnnotation(this);
	}
	
	/**s
	 * @param index
	 * @param bugCollection TODO
	 */
	public void setUserDesignationKeyIndex(int index, @CheckForNull BugCollection bugCollection) {
		setUserDesignationKey(
				I18N.instance().getUserDesignationKey(index), bugCollection);
		}

	/**
	 * Set the user annotation text.
	 *
	 * @param annotationText the user annotation text
	 * @param bugCollection TODO
	 */
	public void setAnnotationText(String annotationText, @CheckForNull BugCollection bugCollection) {
		final BugDesignation u = getNonnullUserDesignation();
		String existingText = u.getAnnotationText();
		if (existingText != null && existingText.equals(annotationText))
			return;
		u.setAnnotationText(annotationText);
		Cloud plugin = bugCollection != null? bugCollection.getCloud() : null;
		if (plugin != null)
			plugin.storeUserAnnotation(this);
	}

	/**
	 * Get the user annotation text.
	 *
	 * @return the user annotation text
	 */
	@NonNull public String getAnnotationText() {
		BugDesignation userDesignation = this.userDesignation;
		if (userDesignation == null) return "";		
		String s = userDesignation.getAnnotationText();
		if (s == null) return "";
		return s;
	}

	public void setUser(String user) {
		BugDesignation userDesignation = getNonnullUserDesignation();
		userDesignation.setUser(user);
	}
	public void setUserAnnotationTimestamp(long timestamp) {
		BugDesignation userDesignation = getNonnullUserDesignation();
		userDesignation.setTimestamp(timestamp);
	}
	/**
	 * Determine whether or not the annotation text contains
	 * the given word.
	 *
	 * @param word the word
	 * @return true if the annotation text contains the word, false otherwise
	 */
	public boolean annotationTextContainsWord(String word) {
		return getTextAnnotationWords().contains(word);
	}

	/**
	 * Get set of words in the text annotation.
	 */
	public Set<String> getTextAnnotationWords() {
		HashSet<String> result = new HashSet<String>();

		StringTokenizer tok = new StringTokenizer(getAnnotationText(), " \t\r\n\f.,:;-");
		while (tok.hasMoreTokens()) {
			result.add(tok.nextToken());
		}
		return result;
	}





	/* ----------------------------------------------------------------------
	 * Property accessors
	 * ---------------------------------------------------------------------- */

	private class BugPropertyIterator implements Iterator<BugProperty> {
		private BugProperty prev, cur;
		private boolean removed;

		/* (non-Javadoc)
		 * @see java.util.Iterator#hasNext()
		 */
		public boolean hasNext() {
			return findNext() != null;
		}
		/* (non-Javadoc)
		 * @see java.util.Iterator#next()
		 */
		public BugProperty next() {
			BugProperty next = findNext();
			if (next == null)
				throw new NoSuchElementException();
			prev = cur;
			cur = next;
			removed = false;
			return cur;
		}

		/* (non-Javadoc)
		 * @see java.util.Iterator#remove()
		 */
		public void remove() {
			if (cur == null || removed)
				throw new IllegalStateException();
			if (prev == null) {
				propertyListHead = cur.getNext();
			} else {
				prev.setNext(cur.getNext());
			}
			if (cur == propertyListTail) {
				propertyListTail = prev;
			}
			removed = true;
		}

		private BugProperty findNext() {
			return cur == null ? propertyListHead : cur.getNext();
		}

	}

	/**
	 * Get value of given property.
	 * 
	 * @param name name of the property to get
	 * @return the value of the named property, or null if
	 *         the property has not been set
	 */
	public String getProperty(String name) {
		BugProperty prop = lookupProperty(name);
		return prop != null ? prop.getValue() : null;
	}

	/**
	 * Get value of given property, returning given default
	 * value if the property has not been set.
	 * 
	 * @param name         name of the property to get
	 * @param defaultValue default value to return if propery is not set
	 * @return the value of the named property, or the default
	 *         value if the property has not been set
	 */
	public String getProperty(String name, String defaultValue) {
		String value = getProperty(name);
		return value != null ? value : defaultValue;
	}

	/**
	 * Get an Iterator over the properties defined in this BugInstance.
	 * 
	 * @return Iterator over properties
	 */
	public Iterator<BugProperty> propertyIterator() {
		return new BugPropertyIterator();
	}

	/**
	 * Set value of given property.
	 * 
	 * @param name  name of the property to set
	 * @param value the value of the property
	 * @return this object, so calls can be chained
	 */
	public BugInstance setProperty(String name, String value) {
		BugProperty prop = lookupProperty(name);
		if (prop != null) {
			prop.setValue(value);
		} else {
			prop = new BugProperty(name, value);
			addProperty(prop);
		}
		return this;
	}

	/**
	 * Look up a property by name.
	 * 
	 * @param name name of the property to look for
	 * @return the BugProperty with the given name,
	 *         or null if the property has not been set
	 */
	public BugProperty lookupProperty(String name) {
		BugProperty prop = propertyListHead;

		while (prop != null) {
			if (prop.getName().equals(name))
				break;
			prop = prop.getNext();
		}

		return prop;
	}

	/**
	 * Delete property with given name.
	 * 
	 * @param name name of the property to delete
	 * @return true if a property with that name was deleted,
	 *         or false if there is no such property
	 */
	public boolean deleteProperty(String name) {
		BugProperty prev = null;
		BugProperty prop = propertyListHead;

		while (prop != null) {
			if (prop.getName().equals(name))
				break;
			prev = prop;
			prop = prop.getNext();
		}

		if (prop != null) {
			if (prev != null) {
				// Deleted node in interior or at tail of list
				prev.setNext(prop.getNext());
			} else {
				// Deleted node at head of list
				propertyListHead = prop.getNext();
			}

			if (prop.getNext() == null) {
				// Deleted node at end of list
				propertyListTail = prev;
			}

			return true;
		} else {
			// No such property
			return false;
		}
	}

	private void addProperty(BugProperty prop) {
		if (propertyListTail != null) {
			propertyListTail.setNext(prop);
			propertyListTail = prop;
		} else {
			propertyListHead = propertyListTail = prop;
		}
		prop.setNext(null);
	}

	/* ----------------------------------------------------------------------
	 * Generic BugAnnotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a Collection of BugAnnotations.
	 * 
	 * @param annotationCollection Collection of BugAnnotations
	 */
	public BugInstance addAnnotations(Collection<? extends BugAnnotation> annotationCollection) {
		for (BugAnnotation annotation : annotationCollection) {
			add(annotation);
		}
		return this;
	}

	/* ----------------------------------------------------------------------
	 * Combined annotation adders
	 * ---------------------------------------------------------------------- */

	public BugInstance addClassAndMethod(MethodDescriptor methodDescriptor) {
		addClass(ClassName.toDottedClassName(methodDescriptor.getSlashedClassName()));
		add(MethodAnnotation.fromMethodDescriptor(methodDescriptor));
		return this;
	}
	public BugInstance addClassAndMethod(XMethod xMethod) {
		return addClassAndMethod(xMethod.getMethodDescriptor());
	}
	/**
	 * Add a class annotation and a method annotation for the class and method
	 * which the given visitor is currently visiting.
	 *
	 * @param visitor the BetterVisitor
	 * @return this object
	 */
	public BugInstance addClassAndMethod(PreorderVisitor visitor) {
		addClass(visitor);
		addMethod(visitor);
		return this;
	}

	/**
	 * Add class and method annotations for given method.
	 *
	 * @param methodAnnotation  the method
	 * @return this object
	 */
	public BugInstance addClassAndMethod(MethodAnnotation methodAnnotation) {
		addClass(methodAnnotation.getClassName());
		addMethod(methodAnnotation);
		return this;
	}

	/**
	 * Add class and method annotations for given method.
	 *
	 * @param methodGen  the method
	 * @param sourceFile source file the method is defined in
	 * @return this object
	 */
	public BugInstance addClassAndMethod(MethodGen methodGen, String sourceFile) {
		addClass(methodGen.getClassName());
		addMethod(methodGen, sourceFile);
		return this;
	}


	/**
	 * Add class and method annotations for given class and method.
	 *  
	 * @param javaClass the class
	 * @param method    the method
	 * @return this object
	 */
	public BugInstance addClassAndMethod(JavaClass javaClass, Method method) {
		addClass(javaClass.getClassName());
		addMethod(javaClass, method);
		return this;
	}

	/* ----------------------------------------------------------------------
	 * Class annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a class annotation.  If this is the first class annotation added,
	 * it becomes the primary class annotation.
	 *
	 * @param className the name of the class
	 * @param sourceFileName the source file of the class
	 * @return this object
	 * @deprecated use addClass(String) instead
	 */
	@Deprecated
	public BugInstance addClass(String className, String sourceFileName) {
		ClassAnnotation classAnnotation = new ClassAnnotation(className);
		add(classAnnotation);
		return this;
	}

	/**
	 * Add a class annotation.  If this is the first class annotation added,
	 * it becomes the primary class annotation.
	 *
	 * @param className the name of the class
	 * @return this object
	 */
	public BugInstance addClass(@DottedClassName String className) {
		ClassAnnotation classAnnotation = new ClassAnnotation(ClassName.toDottedClassName(className));
		add(classAnnotation);
		return this;
	}

	/**
	 * Add a class annotation for the classNode.
	 *
	 * @param classNode the ASM visitor
	 * @return this object
	 */
	public BugInstance addClass(ClassNode classNode) {
		ClassAnnotation classAnnotation = new ClassAnnotation(classNode.name);
		add(classAnnotation);
		return this;
	}

	/**
	 * Add a class annotation.  If this is the first class annotation added,
	 * it becomes the primary class annotation.
	 * 
	 * @param classDescriptor the class to add
	 * @return this object
	 */
	public BugInstance addClass(ClassDescriptor classDescriptor) {
		add(ClassAnnotation.fromClassDescriptor(classDescriptor));
		return this;
	}

	/**
	 * Add a class annotation.  If this is the first class annotation added,
	 * it becomes the primary class annotation.
	 *
	 * @param jclass the JavaClass object for the class
	 * @return this object
	 */
	public BugInstance addClass(JavaClass jclass) {
		addClass(jclass.getClassName());
		return this;
	}

	/**
	 * Add a class annotation for the class that the visitor is currently visiting.
	 *
	 * @param visitor the BetterVisitor
	 * @return this object
	 */
	public BugInstance addClass(PreorderVisitor visitor) {
		String className = visitor.getDottedClassName();
		addClass(className);
		return this;
	}

	/**
	 * Add a class annotation for the superclass of the class the visitor
	 * is currently visiting.
	 *
	 * @param visitor the BetterVisitor
	 * @return this object
	 */
	public BugInstance addSuperclass(PreorderVisitor visitor) {
		String className = ClassName.toDottedClassName(visitor.getSuperclassName());
		addClass(className);
		return this;
	}

	/* ----------------------------------------------------------------------
	 * Type annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a type annotation. Handy for referring to array types.
	 *
	 * <p>For information on type descriptors,
	 * <br>see http://java.sun.com/docs/books/vmspec/2nd-edition/html/ClassFile.doc.html#14152
	 * <br>or  http://www.murrayc.com/learning/java/java_classfileformat.shtml#TypeDescriptors
	 * 
	 * @param typeDescriptor a jvm type descriptor, such as "[I"
	 * @return this object
	 */
	public BugInstance addType(String typeDescriptor) {
		TypeAnnotation typeAnnotation = new TypeAnnotation(typeDescriptor);
		add(typeAnnotation);
		return this;
	}

	public BugInstance addType(Type type) {
		TypeAnnotation typeAnnotation = new TypeAnnotation(type);
		add(typeAnnotation);
		return this;
	}

	public BugInstance addFoundAndExpectedType(Type foundType, Type expectedType) {
		
		add( new TypeAnnotation(foundType, TypeAnnotation.FOUND_ROLE));
		add( new TypeAnnotation(expectedType, TypeAnnotation.EXPECTED_ROLE));
		return this;
	}
	
	public BugInstance addFoundAndExpectedType(String foundType, String expectedType) {
		add( new TypeAnnotation(foundType, TypeAnnotation.FOUND_ROLE));
		add( new TypeAnnotation(expectedType, TypeAnnotation.EXPECTED_ROLE));
		return this;
	}
	
	public BugInstance addEqualsMethodUsed(ClassDescriptor expectedClass) {
		try {
			Set<XMethod> targets = Hierarchy2.resolveVirtualMethodCallTargets(expectedClass, "equals", "(Ljava/lang/Object;)Z",
			        false, false);
			addEqualsMethodUsed(targets);

		} catch (ClassNotFoundException e) {
			AnalysisContext.reportMissingClass(e);
		}

		return this;
	}

	public BugInstance addEqualsMethodUsed(@CheckForNull Collection<XMethod> equalsMethods) {
		if (equalsMethods == null) return this;
		if (equalsMethods.size() < 5) {
			for (XMethod m : equalsMethods) {
				addMethod(m).describe(MethodAnnotation.METHOD_EQUALS_USED);
			}
		} else {
			addMethod(equalsMethods.iterator().next()).describe(MethodAnnotation.METHOD_EQUALS_USED);
		}

		return this;
	}
	public BugInstance addTypeOfNamedClass(String typeName) {
		TypeAnnotation typeAnnotation = new TypeAnnotation("L" + typeName.replace('.','/')+";");
		add(typeAnnotation);
		return this;
	}
	public BugInstance addType(ClassDescriptor c) {
		TypeAnnotation typeAnnotation = new TypeAnnotation("L" + c.getClassName()+";");
		add(typeAnnotation);
		return this;
	}
	/* ----------------------------------------------------------------------
	 * Field annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a field annotation.
	 *
	 * @param className name of the class containing the field
	 * @param fieldName the name of the field
	 * @param fieldSig  type signature of the field
	 * @param isStatic  whether or not the field is static
	 * @return this object
	 */
	public BugInstance addField(String className, String fieldName, String fieldSig, boolean isStatic) {
		addField(new FieldAnnotation(className, fieldName, fieldSig, isStatic));
		return this;
	}

	/**
	 * Add a field annotation.
	 *
	 * @param className name of the class containing the field
	 * @param fieldName the name of the field
	 * @param fieldSig  type signature of the field
	 * @param accessFlags access flags for the field
	 * @return this object
	 */
	public BugInstance addField(String className, String fieldName, String fieldSig, int accessFlags) {
		addField(new FieldAnnotation(className, fieldName, fieldSig, accessFlags));
		return this;
	}
	public BugInstance addField(PreorderVisitor visitor) {
		FieldAnnotation fieldAnnotation = FieldAnnotation.fromVisitedField(visitor);
		return addField(fieldAnnotation);
	}
	/**
	 * Add a field annotation
	 *
	 * @param fieldAnnotation the field annotation
	 * @return this object
	 */
	public BugInstance addField(FieldAnnotation fieldAnnotation) {
		add(fieldAnnotation);
		return this;
	}

	/**
	 * Add a field annotation for a FieldVariable matched in a ByteCodePattern.
	 *
	 * @param field the FieldVariable
	 * @return this object
	 */
	public BugInstance addField(FieldVariable field) {
		return addField(field.getClassName(), field.getFieldName(), field.getFieldSig(), field.isStatic());
	}

	/**
	 * Add a field annotation for an XField.
	 *
	 * @param xfield the XField
	 * @return this object
	 */
	public BugInstance addOptionalField(@CheckForNull XField xfield) {
		if (xfield == null) return this;
		return addField(xfield.getClassName(), xfield.getName(), xfield.getSignature(), xfield.isStatic());
	}
	/**
	 * Add a field annotation for an XField.
	 *
	 * @param xfield the XField
	 * @return this object
	 */
	public BugInstance addField(XField xfield) {
		return addField(xfield.getClassName(), xfield.getName(), xfield.getSignature(), xfield.isStatic());
	}
	/**
	 * Add a field annotation for a FieldDescriptor.
	 * 
	 * @param fieldDescriptor the FieldDescriptor
	 * @return this object
	 */
	public BugInstance addField(FieldDescriptor fieldDescriptor) {
		FieldAnnotation fieldAnnotation = FieldAnnotation.fromFieldDescriptor(fieldDescriptor);
		add(fieldAnnotation);
		return this;
	}

	/**
	 * Add a field annotation for the field which has just been accessed
	 * by the method currently being visited by given visitor.
	 * Assumes that a getfield/putfield or getstatic/putstatic
	 * has just been seen.
	 *
	 * @param visitor the DismantleBytecode object
	 * @return this object
	 */
	public BugInstance addReferencedField(DismantleBytecode visitor) {
		FieldAnnotation f = FieldAnnotation.fromReferencedField(visitor);
		addField(f);
		return this;
	}

	/**
	 * Add a field annotation for the field referenced by the FieldAnnotation parameter
	 */
	public BugInstance addReferencedField(FieldAnnotation fa) {
		addField(fa);
		return this;
	}

	/**
	 * Add a field annotation for the field which is being visited by
	 * given visitor.
	 *
	 * @param visitor the visitor
	 * @return this object
	 */
	public BugInstance addVisitedField(PreorderVisitor visitor) {
		FieldAnnotation f = FieldAnnotation.fromVisitedField(visitor);
		addField(f);
		return this;
	}

	/**
	 * Local variable adders
	 */
	public BugInstance addOptionalLocalVariable(DismantleBytecode dbc, OpcodeStack.Item item) {
		int register = item.getRegisterNumber();

		if (register >= 0)
			this.add(LocalVariableAnnotation.getLocalVariableAnnotation(dbc.getMethod(), register, dbc.getPC()-1, dbc.getPC()));
		return this;
	}
	
	
	/* ----------------------------------------------------------------------
	 * Method annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 *
	 * @param className  name of the class containing the method
	 * @param methodName name of the method
	 * @param methodSig  type signature of the method
	 * @param isStatic   true if the method is static, false otherwise
	 * @return this object
	 */
	public BugInstance addMethod(String className, String methodName, String methodSig, boolean isStatic) {
		addMethod(MethodAnnotation.fromForeignMethod(className, methodName, methodSig, isStatic));
		return this;
	}

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 *
	 * @param className  name of the class containing the method
	 * @param methodName name of the method
	 * @param methodSig  type signature of the method
	 * @param accessFlags   accessFlags for the method
	 * @return this object
	 */
	public BugInstance addMethod(String className, String methodName, String methodSig, int accessFlags) {
		addMethod(MethodAnnotation.fromForeignMethod(className, methodName, methodSig, accessFlags));
		return this;
	}

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 * If the method has source line information, then a SourceLineAnnotation
	 * is added to the method.
	 *
	 * @param methodGen  the MethodGen object for the method
	 * @param sourceFile source file method is defined in
	 * @return this object
	 */
	public BugInstance addMethod(MethodGen methodGen, String sourceFile) {
		String className = methodGen.getClassName();
		MethodAnnotation methodAnnotation =
				new MethodAnnotation(className, methodGen.getName(), methodGen.getSignature(), methodGen.isStatic());
		addMethod(methodAnnotation);
		addSourceLinesForMethod(methodAnnotation, SourceLineAnnotation.fromVisitedMethod(methodGen, sourceFile));
		return this;
	}

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 * If the method has source line information, then a SourceLineAnnotation
	 * is added to the method.
	 *
	 * @param javaClass the class the method is defined in
	 * @param method    the method
	 * @return this object
	 */
	public BugInstance addMethod(JavaClass javaClass, Method method) {
		MethodAnnotation methodAnnotation =
			new MethodAnnotation(javaClass.getClassName(), method.getName(), method.getSignature(), method.isStatic());
		SourceLineAnnotation methodSourceLines = SourceLineAnnotation.forEntireMethod(
				javaClass,
				method);
		methodAnnotation.setSourceLines(methodSourceLines);
		addMethod(methodAnnotation);
		return this;
	}

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 * If the method has source line information, then a SourceLineAnnotation
	 * is added to the method.
	 *
	 * @param classAndMethod JavaClassAndMethod identifying the method to add
	 * @return this object
	 */
	public BugInstance addMethod(JavaClassAndMethod classAndMethod) {
		return addMethod(classAndMethod.getJavaClass(), classAndMethod.getMethod());
	}

	/**
	 * Add a method annotation for the method which the given visitor is currently visiting.
	 * If the method has source line information, then a SourceLineAnnotation
	 * is added to the method.
	 *
	 * @param visitor the BetterVisitor
	 * @return this object
	 */
	public BugInstance addMethod(PreorderVisitor visitor) {
		MethodAnnotation methodAnnotation = MethodAnnotation.fromVisitedMethod(visitor);
		addMethod(methodAnnotation);
		addSourceLinesForMethod(methodAnnotation, SourceLineAnnotation.fromVisitedMethod(visitor));
		return this;
	}

	/**
	 * Add a method annotation for the method which has been called
	 * by the method currently being visited by given visitor.
	 * Assumes that the visitor has just looked at an invoke instruction
	 * of some kind.
	 *
	 * @param visitor the DismantleBytecode object
	 * @return this object
	 */
	public BugInstance addCalledMethod(DismantleBytecode visitor) {
		return addMethod(MethodAnnotation.fromCalledMethod(visitor)).describe(MethodAnnotation.METHOD_CALLED);
	}

	/**
	 * Add a method annotation.
	 *
	 * @param className  name of class containing called method
	 * @param methodName name of called method
	 * @param methodSig  signature of called method
	 * @param isStatic   true if called method is static, false if not
	 * @return this object
	 */
	public BugInstance addCalledMethod(String className, String methodName, String methodSig, boolean isStatic) {
		return addMethod(MethodAnnotation.fromCalledMethod(className, methodName, methodSig, isStatic)).describe(MethodAnnotation.METHOD_CALLED);
	}

	/**
	 * Add a method annotation for the method which is called by given
	 * instruction.
	 *
	 * @param cpg the constant pool for the method containing the call
	 * @param inv the InvokeInstruction
	 * @return this object
	 */
	public BugInstance addCalledMethod(ConstantPoolGen cpg, InvokeInstruction inv) {
		String className = inv.getClassName(cpg);
		String methodName = inv.getMethodName(cpg);
		String methodSig = inv.getSignature(cpg);
		addMethod(className, methodName, methodSig, inv.getOpcode() == Constants.INVOKESTATIC);
		describe(MethodAnnotation.METHOD_CALLED);
		return this;
	}

	/**
	 * Add a method annotation for the method which is called by given
	 * instruction.
	 *
	 * @param methodGen the method containing the call
	 * @param inv       the InvokeInstruction
	 * @return this object
	 */
	public BugInstance addCalledMethod(MethodGen methodGen, InvokeInstruction inv) {
		ConstantPoolGen cpg = methodGen.getConstantPool();
		return addCalledMethod(cpg, inv);
	}

	/**
	 * Add a MethodAnnotation from an XMethod.
	 * 
	 * @param xmethod the XMethod
	 * @return this object
	 */
	public BugInstance addMethod(XMethod xmethod) {
		addMethod(MethodAnnotation.fromXMethod(xmethod));
		return this;
	}

	/**
	 * Add a method annotation.  If this is the first method annotation added,
	 * it becomes the primary method annotation.
	 *
	 * @param methodAnnotation the method annotation
	 * @return this object
	 */
	public BugInstance addMethod(MethodAnnotation methodAnnotation) {
		add(methodAnnotation);
		return this;
	}

	/* ----------------------------------------------------------------------
	 * Integer annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add an integer annotation.
	 *
	 * @param value the integer value
	 * @return this object
	 */
	public BugInstance addInt(int value) {
		add(new IntAnnotation(value));
		return this;
	}

	/*
	 * Add an annotation about a parameter
	 * @param index  parameter index, starting from 0
	 * @param role the role used to describe the parameter
	 */
	public BugInstance addParameterAnnotation(int index, String role) {
		return addInt(index+1).describe(role);
	}
	/**
	 * Add a String annotation.
	 *
	 * @param value the String value
	 * @return this object
	 */
	public BugInstance addString(String value) {
		add(new StringAnnotation(value));
		return this;
	}

	/**
	 * Add a String annotation.
	 *
	 * @param c the char value
	 * @return this object
	 */
	public BugInstance addString(char c) {
		add(new StringAnnotation(Character.toString(c)));
		return this;
	}
	/* ----------------------------------------------------------------------
	 * Source line annotation adders
	 * ---------------------------------------------------------------------- */

	/**
	 * Add a source line annotation.
	 *
	 * @param sourceLine the source line annotation
	 * @return this object
	 */
	public BugInstance addSourceLine(SourceLineAnnotation sourceLine) {
		add(sourceLine);
		return this;
	}

	/**
	 * Add a source line annotation for instruction whose PC is given
	 * in the method that the given visitor is currently visiting.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param visitor a BytecodeScanningDetector that is currently visiting the method
	 * @param pc      bytecode offset of the instruction
	 * @return this object
	 */
	public BugInstance addSourceLine(BytecodeScanningDetector visitor, int pc) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstruction(visitor.getClassContext(), visitor, pc);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a source line annotation for instruction whose PC is given
	 * in the method that the given visitor is currently visiting.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param classContext the ClassContext
	 * @param visitor a PreorderVisitor that is currently visiting the method
	 * @param pc      bytecode offset of the instruction
	 * @return this object
	 */
	public BugInstance addSourceLine(ClassContext classContext, PreorderVisitor visitor, int pc) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstruction(classContext, visitor, pc);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a source line annotation for the given instruction in the given method.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param classContext the ClassContext
	 * @param methodGen  the method being visited
	 * @param sourceFile source file the method is defined in
	 * @param handle     the InstructionHandle containing the visited instruction
	 * @return this object
	 */
	public BugInstance addSourceLine(ClassContext classContext, MethodGen methodGen, String sourceFile, @NonNull InstructionHandle handle) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstruction(classContext, methodGen, sourceFile, handle);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a source line annotation describing a range of instructions.
	 *
	 * @param classContext the ClassContext
	 * @param methodGen  the method
	 * @param sourceFile source file the method is defined in
	 * @param start      the start instruction in the range
	 * @param end        the end instruction in the range (inclusive)
	 * @return this object
	 */
	public BugInstance addSourceLine(ClassContext classContext, MethodGen methodGen, String sourceFile, InstructionHandle start, InstructionHandle end) {
		// Make sure start and end are really in the right order.
		if (start.getPosition() > end.getPosition()) {
			InstructionHandle tmp = start;
			start = end;
			end = tmp;
		}
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstructionRange(classContext, methodGen, sourceFile, start, end);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add source line annotation for given Location in a method. 
	 * 
	 * @param classContext the ClassContext
	 * @param method       the Method
	 * @param location     the Location in the method
	 * @return this BugInstance
	 */
	public BugInstance addSourceLine(ClassContext classContext, Method method, Location location) {
		return addSourceLine(classContext, method, location.getHandle());
	}

	/**
	 * Add source line annotation for given Location in a method.
	 * 
	 * @param methodDescriptor the method
	 * @param location         the Location in the method
	 * @return this BugInstance
	 */
	public BugInstance addSourceLine(MethodDescriptor methodDescriptor, Location location) {
		try {
			IAnalysisCache analysisCache = Global.getAnalysisCache();
			ClassContext classContext = analysisCache.getClassAnalysis(ClassContext.class, methodDescriptor.getClassDescriptor());
			Method method = analysisCache.getMethodAnalysis(Method.class, methodDescriptor);
			return addSourceLine(classContext, method, location);
		} catch (CheckedAnalysisException e) {
			return addSourceLine(SourceLineAnnotation.createReallyUnknown(methodDescriptor.getClassDescriptor().toDottedClassName()));
		}
	}
	
	/**
	 * Add source line annotation for given Location in a method. 
	 * 
	 * @param classContext the ClassContext
	 * @param method       the Method
	 * @param handle       InstructionHandle of an instruction in the method
	 * @return this BugInstance
	 */
	public BugInstance addSourceLine(ClassContext classContext, Method method, InstructionHandle handle) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstruction(classContext, method, handle.getPosition());
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}
	/**
	 * Add a source line annotation describing the
	 * source line numbers for a range of instructions in the method being
	 * visited by the given visitor.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param visitor a BetterVisitor which is visiting the method
	 * @param startPC the bytecode offset of the start instruction in the range
	 * @param endPC   the bytecode offset of the end instruction in the range
	 * @return this object
	 */
	public BugInstance addSourceLineRange(BytecodeScanningDetector visitor, int startPC, int endPC) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstructionRange(visitor.getClassContext(), visitor, startPC, endPC);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a source line annotation describing the
	 * source line numbers for a range of instructions in the method being
	 * visited by the given visitor.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param classContext the ClassContext
	 * @param visitor a BetterVisitor which is visiting the method
	 * @param startPC the bytecode offset of the start instruction in the range
	 * @param endPC   the bytecode offset of the end instruction in the range
	 * @return this object
	 */
	public BugInstance addSourceLineRange(ClassContext classContext, PreorderVisitor visitor, int startPC, int endPC) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstructionRange(classContext, visitor, startPC, endPC);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a source line annotation for instruction currently being visited
	 * by given visitor.
	 * Note that if the method does not have line number information, then
	 * no source line annotation will be added.
	 *
	 * @param visitor a BytecodeScanningDetector visitor that is currently visiting the instruction
	 * @return this object
	 */
	public BugInstance addSourceLine(BytecodeScanningDetector visitor) {
		SourceLineAnnotation sourceLineAnnotation =
			SourceLineAnnotation.fromVisitedInstruction(visitor);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/**
	 * Add a non-specific source line annotation.
	 * This will result in the entire source file being displayed.
	 *
	 * @param className  the class name
	 * @param sourceFile the source file name
	 * @return this object
	 */
	public BugInstance addUnknownSourceLine(String className, String sourceFile) {
		SourceLineAnnotation sourceLineAnnotation = SourceLineAnnotation.createUnknown(className, sourceFile);
		if (sourceLineAnnotation != null)
			add(sourceLineAnnotation);
		return this;
	}

	/* ----------------------------------------------------------------------
	 * Formatting support
	 * ---------------------------------------------------------------------- */

	/**
	 * Format a string describing this bug instance.
	 *
	 * @return the description
	 */
	public String getMessageWithoutPrefix() {
		BugPattern bugPattern = getBugPattern();
		String pattern, shortPattern;
		
			pattern = getLongDescription();
			shortPattern = bugPattern.getShortDescription();
		try {
			FindBugsMessageFormat format = new FindBugsMessageFormat(pattern);
			return format.format(annotationList.toArray(new BugAnnotation[annotationList.size()]), getPrimaryClass());
		} catch (RuntimeException e) {
			AnalysisContext.logError("Error generating bug msg ", e);
			return shortPattern + " [Error generating customized description]";
		}
	}
	String getLongDescription() {
		return getBugPattern().getLongDescription().replaceAll("BUG_PATTERN", type);
	}
	public String getAbridgedMessage() {
		BugPattern bugPattern = getBugPattern();
		String pattern, shortPattern;
		if (bugPattern == null) 
			shortPattern = pattern = "Error2: missing bug pattern for key " + type;
		else {
			pattern = getLongDescription().replaceAll(" in \\{1\\}", "");
			shortPattern = bugPattern.getShortDescription();
		}
		try {
			FindBugsMessageFormat format = new FindBugsMessageFormat(pattern);
			return format.format(annotationList.toArray(new BugAnnotation[annotationList.size()]), getPrimaryClass());
		} catch (RuntimeException e) {
			AnalysisContext.logError("Error generating bug msg ", e);
			return shortPattern + " [Error3 generating customized description]";
		}
	}
	/**
	 * Format a string describing this bug instance.
	 *
	 * @return the description
	 */
	public String getMessage() {
		BugPattern bugPattern = getBugPattern();
		String pattern = bugPattern.getAbbrev() + ": " + getLongDescription();
		FindBugsMessageFormat format = new FindBugsMessageFormat(pattern);
		try {
			return format.format(annotationList.toArray(new BugAnnotation[annotationList.size()]), getPrimaryClass());
		} catch (RuntimeException e) {
			AnalysisContext.logError("Error generating bug msg ", e);
			return bugPattern.getShortDescription() + " [Error generating customized description]";
		}
	}

	/**
	 * Format a string describing this bug pattern, with the priority and type at the beginning.
	 * e.g. "(High Priority Correctness) Guaranteed null pointer dereference..."
	 */
	public String getMessageWithPriorityType() {
		return "(" + this.getPriorityTypeString() + ") " + this.getMessage();
	}

	public String getMessageWithPriorityTypeAbbreviation() {
		return this.getPriorityTypeAbbreviation() + " "+ this.getMessage();
	}

	/**
	 * Add a description to the most recently added bug annotation.
	 *
	 * @param description the description to add
	 * @return this object
	 */
	public BugInstance describe(String description) {
		annotationList.get(annotationList.size() - 1).setDescription(description);
		return this;
	}

	/**
	 * Convert to String.
	 * This method returns the "short" message describing the bug,
	 * as opposed to the longer format returned by getMessage().
	 * The short format is appropriate for the tree view in a GUI,
	 * where the annotations are listed separately as part of the overall
	 * bug instance.
	 */
	@Override
	public String toString() {
		return I18N.instance().getShortMessage(type);
	}

	/* ----------------------------------------------------------------------
	 * XML Conversion support
	 * ---------------------------------------------------------------------- */

	public void writeXML(XMLOutput xmlOutput) throws IOException {
		writeXML(xmlOutput, false, false);
	}

	public void writeXML(XMLOutput xmlOutput, boolean addMessages, boolean isPrimary) throws IOException {
		  XMLAttributeList attributeList = new XMLAttributeList()
			.addAttribute("type", type)
			.addAttribute("priority", String.valueOf(priority));

		BugPattern pattern = getBugPattern();
		if (pattern != null) {
			// The bug abbreviation and pattern category are
			// emitted into the XML for informational purposes only.
			// (The information is redundant, but might be useful
			// for processing tools that want to make sense of
			// bug instances without looking at the plugin descriptor.)
			attributeList.addAttribute("abbrev", pattern.getAbbrev());
			attributeList.addAttribute("category", pattern.getCategory());
		}


		if (addMessages) {
			//		Add a uid attribute, if we have a unique id.

			attributeList.addAttribute("instanceHash", getInstanceHash());
			attributeList.addAttribute("instanceOccurrenceNum", Integer.toString(getInstanceOccurrenceNum()));
			attributeList.addAttribute("instanceOccurrenceMax", Integer.toString(getInstanceOccurrenceMax()));

		}
		if (firstVersion > 0) attributeList.addAttribute("first", Long.toString(firstVersion));
		if (lastVersion >= 0) 	attributeList.addAttribute("last", Long.toString(lastVersion));
		if (introducedByChangeOfExistingClass) 
			attributeList.addAttribute("introducedByChange", "true");
		if (removedByChangeOfPersistingClass) 
			attributeList.addAttribute("removedByChange", "true");

		xmlOutput.openTag(ELEMENT_NAME, attributeList);

		if (userDesignation != null) {
			userDesignation.writeXML(xmlOutput);
		}

		if (addMessages) {
			BugPattern bugPattern = getBugPattern();

			xmlOutput.openTag("ShortMessage");
			xmlOutput.writeText(bugPattern != null ? bugPattern.getShortDescription() : this.toString());
			xmlOutput.closeTag("ShortMessage");

			xmlOutput.openTag("LongMessage");
			if (FindBugsDisplayFeatures.isAbridgedMessages()) xmlOutput.writeText(this.getAbridgedMessage());
			else xmlOutput.writeText(this.getMessageWithoutPrefix());
			xmlOutput.closeTag("LongMessage");
		}

		Map<BugAnnotation,Void> primaryAnnotations;
		
		if (addMessages) {
			primaryAnnotations = new IdentityHashMap<BugAnnotation,Void>();
			primaryAnnotations.put(getPrimarySourceLineAnnotation(), null);
			primaryAnnotations.put(getPrimaryClass(), null);
			primaryAnnotations.put(getPrimaryField(), null);
			primaryAnnotations.put(getPrimaryMethod(), null);
		} else {
			primaryAnnotations = Collections.<BugAnnotation,Void>emptyMap();
		}
		
		boolean foundSourceAnnotation = false;
		for (BugAnnotation annotation : annotationList) {
			if (annotation instanceof SourceLineAnnotation) 
				foundSourceAnnotation = true;
			annotation.writeXML(xmlOutput, addMessages,  primaryAnnotations.containsKey(annotation));
		}
		if (!foundSourceAnnotation && addMessages) {
			SourceLineAnnotation synth = getPrimarySourceLineAnnotation();
			if (synth != null) {
				synth.setSynthetic(true);
				synth.writeXML(xmlOutput, addMessages, false);
			}
		}

		if (propertyListHead != null) {
			BugProperty prop = propertyListHead;
			while (prop != null) {
				prop.writeXML(xmlOutput);
				prop = prop.getNext();
			}
		}

		xmlOutput.closeTag(ELEMENT_NAME);
	}

	private static final String ELEMENT_NAME = "BugInstance";
	private static final String USER_ANNOTATION_ELEMENT_NAME = "UserAnnotation";

	/* ----------------------------------------------------------------------
	 * Implementation
	 * ---------------------------------------------------------------------- */

	public BugInstance addOptionalAnnotation(@CheckForNull BugAnnotation annotation) {
		if (annotation == null) return this;
		return add(annotation);
	}
	public BugInstance addOptionalAnnotation(@CheckForNull BugAnnotation annotation, String role) {
		if (annotation == null) return this;
		return add(annotation).describe(role);
	}
	public BugInstance add(@Nonnull BugAnnotation annotation) {
		if (annotation == null)
			throw new IllegalArgumentException("Missing BugAnnotation!");

		// Add to list
		annotationList.add(annotation);

		// This object is being modified, so the cached hashcode
		// must be invalidated
		cachedHashCode = INVALID_HASH_CODE;
		return this;
	}
	
	public BugInstance addSomeSourceForTopTwoStackValues(ClassContext classContext, Method method, Location location) {
		int pc = location.getHandle().getPosition();
		OpcodeStack stack = OpcodeStackScanner.getStackAt(classContext.getJavaClass(), method, pc);
		BugAnnotation a1 = getSomeSource(classContext, method, location, stack, 1);
		BugAnnotation a0 = getSomeSource(classContext, method, location, stack, 0);
		addOptionalUniqueAnnotations(a0, a1);
		
			
		return this;

	}
	
	public BugInstance addSourceForTopStackValue(ClassContext classContext, Method method, Location location) {
		BugAnnotation b =  getSourceForTopStackValue( classContext,  method,  location) ;
		return this.addOptionalAnnotation(b);
	}
	public static @CheckForNull BugAnnotation getSourceForTopStackValue(ClassContext classContext, Method method, Location location) {
		int pc = location.getHandle().getPosition();
		OpcodeStack stack = OpcodeStackScanner.getStackAt(classContext.getJavaClass(), method, pc);
		BugAnnotation a0 = getSomeSource(classContext, method, location, stack, 0);
		return a0;
	}
	public static @CheckForNull BugAnnotation getSomeSource(ClassContext classContext, Method method, Location location, OpcodeStack stack, int stackPos) {
		int pc = location.getHandle().getPosition();

		try {
			ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
			if (vnaFrame.isValid()) {
				ValueNumber valueNumber = vnaFrame.getStackValue(stackPos);
				if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT))
					return null;
				BugAnnotation variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location,
				        valueNumber, vnaFrame, "VALUE_OF");
				if (variableAnnotation != null) {
					return variableAnnotation;
				}

			}
		} catch (DataflowAnalysisException e) {
			AnalysisContext.logError("Couldn't find value source", e);
		} catch (CFGBuilderException e) {
			AnalysisContext.logError("Couldn't find value source", e);
		}

		return getValueSource(stack.getStackItem(stackPos), method, pc);

	}

	public static BugAnnotation getValueSource(OpcodeStack.Item item, Method method, int pc) {
		LocalVariableAnnotation lv = LocalVariableAnnotation.getLocalVariableAnnotation(method, item, pc);
		if (lv != null && lv.isNamed()) 
			return lv;
		
		return getFieldOrMethodValueSource(item);
		

	}

	public BugInstance addValueSource(OpcodeStack.Item item, DismantleBytecode dbc) {
		return addValueSource(item, dbc.getMethod(), dbc.getPC());
	}
	
	public BugInstance addValueSource(OpcodeStack.Item item, Method method, int pc) {
		addOptionalAnnotation(getValueSource(item, method, pc));
		return this;
	}
	/**
     * @param item
     */
    public BugInstance addFieldOrMethodValueSource(OpcodeStack.Item item) {
    	addOptionalAnnotation(getFieldOrMethodValueSource(item));
    	return this;
    }

    public BugInstance addOptionalUniqueAnnotations(BugAnnotation... annotations) {
    	HashSet<BugAnnotation> added = new HashSet<BugAnnotation>();
    	for(BugAnnotation a : annotations) if (a != null && added.add(a)) 
    		add(a);
    	return this;
    }
    public BugInstance addOptionalUniqueAnnotationsWithFallback(BugAnnotation fallback, BugAnnotation... annotations) {
    	HashSet<BugAnnotation> added = new HashSet<BugAnnotation>();
    	for(BugAnnotation a : annotations) if (a != null && added.add(a)) 
    		add(a);
    	if (added.isEmpty())
    		add(fallback);
    	return this;
    }
    public static @CheckForNull BugAnnotation getFieldOrMethodValueSource(@CheckForNull OpcodeStack.Item item) {
    	if (item == null) 
    		return null;
		XField xField = item.getXField();
		if (xField != null) {
			FieldAnnotation a = FieldAnnotation.fromXField(xField);
			a.setDescription(FieldAnnotation.LOADED_FROM_ROLE);
			return a;
		}

		XMethod xMethod = item.getReturnValueOf();
		if (xMethod != null) {
			MethodAnnotation a = MethodAnnotation.fromXMethod(xMethod);
			a.setDescription(MethodAnnotation.METHOD_RETURN_VALUE_OF);
			return a;
		}
		return null;

	}

	private void addSourceLinesForMethod(MethodAnnotation methodAnnotation, SourceLineAnnotation sourceLineAnnotation) {
		if (sourceLineAnnotation != null) {
			// Note: we don't add the source line annotation directly to
			// the bug instance.  Instead, we stash it in the MethodAnnotation.
			// It is much more useful there, and it would just be distracting
			// if it were displayed in the UI, since it would compete for attention
			// with the actual bug location source line annotation (which is much
			// more important and interesting).
			methodAnnotation.setSourceLines(sourceLineAnnotation);
		}
	}

	@Override
	public int hashCode() {
		if (cachedHashCode == INVALID_HASH_CODE) {
			int hashcode = type.hashCode() + priority;
			Iterator<BugAnnotation> i = annotationIterator();
			while (i.hasNext())
				hashcode += i.next().hashCode();
			if (hashcode == INVALID_HASH_CODE)
				hashcode = INVALID_HASH_CODE+1;
			cachedHashCode = hashcode;
		}

		return cachedHashCode;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof BugInstance))
			return false;
		BugInstance other = (BugInstance) o;
		if (!type.equals(other.type) || priority != other.priority)
			return false;
		if (annotationList.size() != other.annotationList.size())
			return false;
		int numAnnotations = annotationList.size();
		for (int i = 0; i < numAnnotations; ++i) {
			BugAnnotation lhs = annotationList.get(i);
			BugAnnotation rhs = other.annotationList.get(i);
			if (!lhs.equals(rhs))
				return false;
		}

		return true;
	}

	public int compareTo(BugInstance other) {
		int cmp;
		cmp = type.compareTo(other.type);
		if (cmp != 0)
			return cmp;
		cmp = priority - other.priority;
		if (cmp != 0)
			return cmp;

		// Compare BugAnnotations lexicographically
		int pfxLen = Math.min(annotationList.size(), other.annotationList.size());
		for (int i = 0; i < pfxLen; ++i) {
			BugAnnotation lhs = annotationList.get(i);
			BugAnnotation rhs = other.annotationList.get(i);
			cmp = lhs.compareTo(rhs);
			if (cmp != 0)
				return cmp;
		}

		// All elements in prefix were the same,
		// so use number of elements to decide
		return annotationList.size() - other.annotationList.size();
	}

	/**
	 * @param firstVersion The firstVersion to set.
	 */
	public void setFirstVersion(long firstVersion) {
		this.firstVersion = firstVersion;
		if (lastVersion >= 0 && firstVersion > lastVersion) 
			throw new IllegalArgumentException(
				firstVersion + ".." + lastVersion);
	}

	/**
	 * @return Returns the firstVersion.
	 */
	public long getFirstVersion() {
		return firstVersion;
	}

	/**
	 * @param lastVersion The lastVersion to set.
	 */
	public void setLastVersion(long lastVersion) {
		if (lastVersion >= 0 && firstVersion > lastVersion) 
			throw new IllegalArgumentException(
				firstVersion + ".." + lastVersion);
		this.lastVersion = lastVersion;
	}

	/**
	 * @return Returns the lastVersion.
	 */
	public long getLastVersion() {
		return lastVersion;
	}

	public boolean isDead() {
		return lastVersion != -1;
	}
	/**
	 * @param introducedByChangeOfExistingClass The introducedByChangeOfExistingClass to set.
	 */
	public void setIntroducedByChangeOfExistingClass(boolean introducedByChangeOfExistingClass) {
		this.introducedByChangeOfExistingClass = introducedByChangeOfExistingClass;
	}

	/**
	 * @return Returns the introducedByChangeOfExistingClass.
	 */
	public boolean isIntroducedByChangeOfExistingClass() {
		return introducedByChangeOfExistingClass;
	}

	/**
	 * @param removedByChangeOfPersistingClass The removedByChangeOfPersistingClass to set.
	 */
	public void setRemovedByChangeOfPersistingClass(boolean removedByChangeOfPersistingClass) {
		this.removedByChangeOfPersistingClass = removedByChangeOfPersistingClass;
	}

	/**
	 * @return Returns the removedByChangeOfPersistingClass.
	 */
	public boolean isRemovedByChangeOfPersistingClass() {
		return removedByChangeOfPersistingClass;
	}

	/**
	 * @param instanceHash The instanceHash to set.
	 */
	public void setInstanceHash(String instanceHash) {
		this.instanceHash = instanceHash;
	}
	/**
	 * @param oldInstanceHash The oldInstanceHash to set.
	 */
	public void setOldInstanceHash(String oldInstanceHash) {
		this.oldInstanceHash = oldInstanceHash;
	}
	private static final boolean DONT_HASH =  SystemProperties.getBoolean("findbugs.dontHash");
	/**
	 * @return Returns the instanceHash.
	 */
	public String getInstanceHash() {
		if (instanceHash != null) return instanceHash;
			MessageDigest digest = null;
			try { digest = MessageDigest.getInstance("MD5");
			} catch (Exception e2) {
				// OK, we won't digest
			}
			instanceHash = getInstanceKey();
			if (digest != null && !DONT_HASH) {
				byte [] data = digest.digest(instanceHash.getBytes());
				String tmp = new BigInteger(1,data).toString(16);
				instanceHash = tmp;
			}
			return instanceHash;
	}

	public boolean isInstanceHashConsistent() {
		return oldInstanceHash == null || instanceHash.equals(oldInstanceHash);
	}
	/**
	 * @param instanceOccurrenceNum The instanceOccurrenceNum to set.
	 */
	public void setInstanceOccurrenceNum(int instanceOccurrenceNum) {
		this.instanceOccurrenceNum = instanceOccurrenceNum;
	}

	/**
	 * @return Returns the instanceOccurrenceNum.
	 */
	public int getInstanceOccurrenceNum() {
		return instanceOccurrenceNum;
	}

	/**
	 * @param instanceOccurrenceMax The instanceOccurrenceMax to set.
	 */
	public void setInstanceOccurrenceMax(int instanceOccurrenceMax) {
		this.instanceOccurrenceMax = instanceOccurrenceMax;
	}

	/**
	 * @return Returns the instanceOccurrenceMax.
	 */
	public int getInstanceOccurrenceMax() {
		return instanceOccurrenceMax;
	}
	
	public DetectorFactory getDetectorFactory() {
		return detectorFactory;
	}
}

// vim:ts=4
