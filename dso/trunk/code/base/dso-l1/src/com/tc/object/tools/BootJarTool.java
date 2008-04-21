/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tools;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

import com.tc.asm.ClassReader;
import com.tc.asm.ClassVisitor;
import com.tc.asm.ClassWriter;
import com.tc.asm.MethodVisitor;
import com.tc.asm.commons.SerialVersionUIDAdder;
import com.tc.asm.tree.ClassNode;
import com.tc.asm.tree.InnerClassNode;
import com.tc.asm.tree.MethodNode;
import com.tc.aspectwerkz.reflect.ClassInfo;
import com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo;
import com.tc.aspectwerkz.reflect.impl.java.JavaClassInfo;
import com.tc.cluster.ClusterEventListener;
import com.tc.config.Directories;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L1TVSConfigurationSetupManager;
import com.tc.config.schema.setup.StandardTVSConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.TVSConfigurationSetupManagerFactory;
import com.tc.exception.ExceptionWrapper;
import com.tc.exception.ExceptionWrapperImpl;
import com.tc.exception.TCNotSupportedMethodException;
import com.tc.exception.TCObjectNotFoundException;
import com.tc.exception.TCObjectNotSharableException;
import com.tc.exception.TCRuntimeException;
import com.tc.geronimo.GeronimoLoaderNaming;
import com.tc.hibernate.HibernateProxyInstance;
import com.tc.ibatis.IBatisAccessPlanInstance;
import com.tc.io.TCByteArrayOutputStream;
import com.tc.jboss.JBossLoaderNaming;
import com.tc.logging.CustomerLogging;
import com.tc.logging.NullTCLogger;
import com.tc.logging.TCLogger;
import com.tc.management.TerracottaMBean;
import com.tc.management.beans.sessions.SessionMonitorMBean;
import com.tc.net.NIOWorkarounds;
import com.tc.object.ObjectID;
import com.tc.object.Portability;
import com.tc.object.PortabilityImpl;
import com.tc.object.SerializationUtil;
import com.tc.object.TCClass;
import com.tc.object.TCObject;
import com.tc.object.bytecode.AbstractStringBuilderAdapter;
import com.tc.object.bytecode.AccessibleObjectAdapter;
import com.tc.object.bytecode.ArrayListAdapter;
import com.tc.object.bytecode.AtomicIntegerAdapter;
import com.tc.object.bytecode.AtomicLongAdapter;
import com.tc.object.bytecode.BufferedWriterAdapter;
import com.tc.object.bytecode.ByteCodeUtil;
import com.tc.object.bytecode.ChangeClassNameHierarchyAdapter;
import com.tc.object.bytecode.ChangeClassNameRootAdapter;
import com.tc.object.bytecode.ClassAdapterFactory;
import com.tc.object.bytecode.Clearable;
import com.tc.object.bytecode.DataOutputStreamAdapter;
import com.tc.object.bytecode.DuplicateMethodAdapter;
import com.tc.object.bytecode.HashtableClassAdapter;
import com.tc.object.bytecode.JavaLangReflectArrayAdapter;
import com.tc.object.bytecode.JavaLangReflectFieldAdapter;
import com.tc.object.bytecode.JavaLangReflectProxyClassAdapter;
import com.tc.object.bytecode.JavaLangStringAdapter;
import com.tc.object.bytecode.JavaLangStringTC;
import com.tc.object.bytecode.JavaLangThrowableDebugClassAdapter;
import com.tc.object.bytecode.JavaNetURLAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentCyclicBarrierDebugClassAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapEntryIteratorAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapHashEntryAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapSegmentAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapValueIteratorAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentHashMapWriteThroughEntryAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentLinkedBlockingQueueAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentLinkedBlockingQueueClassAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentLinkedBlockingQueueIteratorClassAdapter;
import com.tc.object.bytecode.JavaUtilConcurrentLinkedBlockingQueueNodeClassAdapter;
import com.tc.object.bytecode.JavaUtilTreeMapAdapter;
import com.tc.object.bytecode.JavaUtilWeakHashMapAdapter;
import com.tc.object.bytecode.LinkedHashMapClassAdapter;
import com.tc.object.bytecode.LinkedListAdapter;
import com.tc.object.bytecode.LogManagerAdapter;
import com.tc.object.bytecode.LogicalClassSerializationAdapter;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.Manager;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.bytecode.MergeTCToJavaClassAdapter;
import com.tc.object.bytecode.NullManager;
import com.tc.object.bytecode.NullTCObject;
import com.tc.object.bytecode.ReentrantLockClassAdapter;
import com.tc.object.bytecode.ReentrantReadWriteLockClassAdapter;
import com.tc.object.bytecode.SetRemoveMethodAdapter;
import com.tc.object.bytecode.StringBufferAdapter;
import com.tc.object.bytecode.StringGetCharsAdapter;
import com.tc.object.bytecode.TCMap;
import com.tc.object.bytecode.TCMapEntry;
import com.tc.object.bytecode.TransparencyClassAdapter;
import com.tc.object.bytecode.TransparentAccess;
import com.tc.object.bytecode.VectorAdapter;
import com.tc.object.bytecode.hook.ClassLoaderPreProcessorImpl;
import com.tc.object.bytecode.hook.ClassPostProcessor;
import com.tc.object.bytecode.hook.ClassPreProcessor;
import com.tc.object.bytecode.hook.ClassProcessor;
import com.tc.object.bytecode.hook.DSOContext;
import com.tc.object.bytecode.hook.impl.ClassProcessorHelper;
import com.tc.object.bytecode.hook.impl.ClassProcessorHelperJDK15;
import com.tc.object.bytecode.hook.impl.JavaLangArrayHelpers;
import com.tc.object.bytecode.hook.impl.SessionsHelper;
import com.tc.object.bytecode.hook.impl.Util;
import com.tc.object.cache.Cacheable;
import com.tc.object.compression.CompressedData;
import com.tc.object.compression.StringCompressionUtil;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.DSOSpringConfigHelper;
import com.tc.object.config.StandardDSOClientConfigHelperImpl;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.object.dna.impl.ProxyInstance;
import com.tc.object.field.TCField;
import com.tc.object.ibm.SystemInitializationAdapter;
import com.tc.object.loaders.BytecodeProvider;
import com.tc.object.loaders.ClassProvider;
import com.tc.object.loaders.NamedClassLoader;
import com.tc.object.loaders.NamedLoaderAdapter;
import com.tc.object.loaders.Namespace;
import com.tc.object.loaders.StandardClassLoaderAdapter;
import com.tc.object.loaders.StandardClassProvider;
import com.tc.object.logging.InstrumentationLogger;
import com.tc.object.logging.InstrumentationLoggerImpl;
import com.tc.object.logging.NullInstrumentationLogger;
import com.tc.object.partitions.PartitionManager;
import com.tc.object.util.OverrideCheck;
import com.tc.object.util.ToggleableStrongReference;
import com.tc.plugins.ModulesLoader;
import com.tc.properties.TCProperties;
import com.tc.session.SessionSupport;
import com.tc.text.Banner;
import com.tc.tomcat.TomcatLoaderNaming;
import com.tc.util.AbstractIdentifier;
import com.tc.util.Assert;
import com.tc.util.DebugUtil;
import com.tc.util.EnumerationWrapper;
import com.tc.util.FieldUtils;
import com.tc.util.HashtableKeySetWrapper;
import com.tc.util.HashtableValuesWrapper;
import com.tc.util.ListIteratorWrapper;
import com.tc.util.ReflectiveProxy;
import com.tc.util.SetIteratorWrapper;
import com.tc.util.THashMapCollectionWrapper;
import com.tc.util.UnsafeUtil;
import com.tc.util.runtime.Os;
import com.tc.util.runtime.UnknownJvmVersionException;
import com.tc.util.runtime.UnknownRuntimeVersionException;
import com.tc.util.runtime.Vm;
import com.tc.util.runtime.VmVersion;
import com.tc.websphere.WebsphereLoaderNaming;
import com.tcclient.util.HashtableEntrySetWrapper;
import com.tcclient.util.MapEntrySetWrapper;

import gnu.trove.TLinkable;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.AccessibleObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tool for creating the DSO boot jar
 */
public class BootJarTool {
  public static final String          TC_DEBUG_THROWABLE_CONSTRUCTION  = "tc.debug.throwable.construction";

  private static final String         EXCESS_CLASSES                   = "excess";
  private static final String         MISSING_CLASSES                  = "missing";

  private final static String         TARGET_FILE_OPTION               = "o";
  private final static boolean        WRITE_OUT_TEMP_FILE              = true;

  private static final String         DEFAULT_CONFIG_SPEC              = "tc-config.xml";

  private final ClassLoader           tcLoader;
  private final ClassLoader           systemLoader;
  private final DSOClientConfigHelper configHelper;
  private final File                  outputFile;
  private final Portability           portability;

  // various sets that are populated while massaging user defined boot jar specs
  private final Set                   notBootstrapClasses              = new HashSet();
  private final Set                   notAdaptableClasses              = new HashSet();
  private final Set                   logicalSubclasses                = new HashSet();
  private final Set                   autoIncludedBootstrapClasses     = new HashSet();
  private final Set                   nonExistingClasses               = new HashSet();

  private InstrumentationLogger       instrumentationLogger;
  private BootJar                     bootJar;
  private BootJarHandler              bootJarHandler;
  private boolean                     quiet;

  public static final String          SYSTEM_CLASSLOADER_NAME_PROPERTY = "com.tc.loader.system.name";
  public static final String          EXT_CLASSLOADER_NAME_PROPERTY    = "com.tc.loader.ext.name";

  private static final TCLogger       consoleLogger                    = CustomerLogging.getConsoleLogger();

  public BootJarTool(DSOClientConfigHelper configuration, File outputFile, ClassLoader systemProvider, boolean quiet) {
    this.configHelper = configuration;
    this.outputFile = outputFile;
    this.systemLoader = systemProvider;
    this.tcLoader = getClass().getClassLoader();
    this.bootJarHandler = new BootJarHandler(WRITE_OUT_TEMP_FILE, this.outputFile);
    this.quiet = quiet;
    this.portability = new PortabilityImpl(this.configHelper);
    ModulesLoader.initModules(this.configHelper, null, true);
  }

  public BootJarTool(DSOClientConfigHelper configuration, File outputFile, ClassLoader systemProvider) {
    this(configuration, outputFile, systemProvider, false);
  }

  private final void addJdk15SpecificPreInstrumentedClasses() {
    if (Vm.isJDK15Compliant()) {
      TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.math.MathContext");
      spec.markPreInstrumented();

      addInstrumentedJavaUtilConcurrentLocks();

      addInstrumentedJavaUtilConcurrentLinkedBlockingQueue();
      addInstrumentedJavaUtilConcurrentHashMap();
      addInstrumentedJavaUtilConcurrentCyclicBarrier();
      addInstrumentedJavaUtilConcurrentFutureTask();
    }
  }

  /**
   * Scans the boot JAR file to determine if it is complete and contains only the user-classes that are specified in the
   * tc-config's <additional-boot-jar-classes/> section. Program execution halts if it fails these checks.
   */
  private final void scanJar(File bootJarFile) {
    if (!bootJarFile.exists()) {
      consoleLogger.fatal("\nDSO boot JAR file not found: '" + bootJarFile
                          + "'; you can specify the boot JAR file to scan using the -o or --bootjar-file option.");
      System.exit(1);
    }
    final String MESSAGE0 = "\nYour boot JAR file might be out of date or invalid.";
    final String MESSAGE1 = "\nThe following classes was declared in the <additional-boot-jar-classes/> section "
                            + "of your tc-config file but is not a part of your boot JAR file:";
    final String MESSAGE2 = "\nThe following user-classes were found in the boot JAR but was not declared in the "
                            + "<additional-boot-jar-classes/> section of your tc-config file:";
    final String MESSAGE3 = "\nUse the make-boot-jar tool to re-create your boot JAR.";
    try {
      final Map result = compareBootJarContentsToUserSpec(bootJarFile);
      final Set missing = (Set) result.get(MISSING_CLASSES);
      final Set excess = (Set) result.get(EXCESS_CLASSES);
      if (!missing.isEmpty() || !excess.isEmpty()) {
        consoleLogger.fatal(MESSAGE0);
        if (!missing.isEmpty()) {
          consoleLogger.error(MESSAGE1);
          for (Iterator i = missing.iterator(); i.hasNext();)
            consoleLogger.error("- " + i.next());
        }
        if (!excess.isEmpty()) {
          consoleLogger.error(MESSAGE2);
          for (Iterator i = missing.iterator(); i.hasNext();)
            consoleLogger.error("- " + i.next());
        }
        consoleLogger.error(MESSAGE3);
        System.exit(1);
      }
    } catch (InvalidBootJarMetaDataException e) {
      consoleLogger.fatal(e.getMessage());
      consoleLogger.fatal(MESSAGE3);
      System.exit(1);
    }
  }

  /**
   * Checks if the given bootJarFile is complete; meaning: - All the classes declared in the configurations
   * <additional-boot-jar-classes/> section is present in the boot jar. - And there are no user-classes present in the
   * boot jar that is not declared in the <additional-boot-jar-classes/> section
   *
   * @return <code>true</code> if the boot jar is complete.
   */
  private final boolean isBootJarComplete(File bootJarFile) {
    try {
      final Map result = compareBootJarContentsToUserSpec(bootJarFile);
      final Set missing = (Set) result.get(MISSING_CLASSES);
      final Set excess = (Set) result.get(EXCESS_CLASSES);
      return missing.isEmpty() && excess.isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private final Map compareBootJarContentsToUserSpec(File bootJarFile) throws InvalidBootJarMetaDataException {
    try {
      // verify that userspec is valid, eg: no non-existent classes declared
      massageSpecs(getUserDefinedSpecs(getTCSpecs()), false);
      issueWarningsAndErrors();

      // check that everything listed in userspec is in the bootjar
      Map result = new HashMap();
      Map userSpecs = massageSpecs(getUserDefinedSpecs(getTCSpecs()), false);
      BootJar bootJarLocal = BootJar.getBootJarForReading(bootJarFile);
      Set preInstrumented = bootJarLocal.getAllPreInstrumentedClasses();
      Set unInstrumented = bootJarLocal.getAllUninstrumentedClasses();
      Set missing = new HashSet();
      for (Iterator i = userSpecs.keySet().iterator(); i.hasNext();) {
        String cn = (String) i.next();
        if (!preInstrumented.contains(cn) && !unInstrumented.contains(cn)) missing.add(cn);
      }

      // check that everything marked as foreign in the bootjar is also
      // listed in the userspec
      result.put(MISSING_CLASSES, missing);
      Set foreign = bootJarLocal.getAllForeignClasses();
      Set excess = new HashSet();
      for (Iterator i = foreign.iterator(); i.hasNext();) {
        String cn = (String) i.next();
        if (!userSpecs.keySet().contains(cn)) excess.add(cn);
      }
      result.put(EXCESS_CLASSES, excess);
      return result;
    } catch (InvalidBootJarMetaDataException e) {
      throw e;
    } catch (BootJarException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * verify that all of the reference TC classes are in the bootjar
   */
  private final void verifyJar(File bootJarFile) {
    try {
      final BootJar bootJarLocal = BootJar.getBootJarForReading(bootJarFile);
      final Set bootJarClassNames = bootJarLocal.getAllClasses();
      Map offendingClasses = new HashMap();
      for (Iterator i = bootJarClassNames.iterator(); i.hasNext();) {
        final String className = (String) i.next();
        final byte[] bytes = bootJarLocal.getBytesForClass(className);
        ClassReader cr = new ClassReader(bytes);
        ClassVisitor cv = new BootJarClassDependencyVisitor(bootJarClassNames, offendingClasses);
        cr.accept(cv, ClassReader.SKIP_FRAMES);
      }

      String nl = System.getProperty("line.separator");
      StringBuffer message = new StringBuffer(
                                              nl
                                                  + nl
                                                  + "The following Terracotta classes needs to be included in the boot jar:"
                                                  + nl + nl);
      for (Iterator i = offendingClasses.entrySet().iterator(); i.hasNext();) {
        Map.Entry entry = (Map.Entry) i.next();
        message.append("  - " + entry.getKey() + " [" + entry.getValue() + "]" + nl);
      }
      Assert.assertTrue(message, offendingClasses.isEmpty());
    } catch (BootJarException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public final void generateJar() {
    instrumentationLogger = new InstrumentationLoggerImpl(configHelper.getInstrumentationLoggingOptions());
    try {
      bootJarHandler.validateDirectoryExists();
    } catch (BootJarHandlerException e) {
      exit(e.getMessage(), e.getCause());
    }

    if (!quiet) {
      bootJarHandler.announceCreationStart();
    }

    try {
      bootJar = bootJarHandler.getBootJar();

      addInstrumentedHashMap();
      addInstrumentedHashtable();
      addInstrumentedJavaUtilCollection();
      addReflectionInstrumentation();

      addJdk15SpecificPreInstrumentedClasses();

      addInstrumentedWeakHashMap();

      loadTerracottaClass(DebugUtil.class.getName());
      loadTerracottaClass(SessionSupport.class.getName());
      loadTerracottaClass(TCMap.class.getName());
      if (Vm.isJDK15Compliant()) {
        loadTerracottaClass("com.tc.util.concurrent.locks.TCLock");
      }
      loadTerracottaClass(com.tc.util.Stack.class.getName());
      loadTerracottaClass(TCObjectNotSharableException.class.getName());
      loadTerracottaClass(TCObjectNotFoundException.class.getName());

      loadTerracottaClass(THashMapCollectionWrapper.class.getName());
      loadTerracottaClass(THashMapCollectionWrapper.class.getName() + "$IteratorWrapper");
      loadTerracottaClass(ListIteratorWrapper.class.getName());
      loadTerracottaClass(MapEntrySetWrapper.class.getName());
      loadTerracottaClass(MapEntrySetWrapper.class.getName() + "$IteratorWrapper");
      loadTerracottaClass(HashtableEntrySetWrapper.class.getName());
      loadTerracottaClass(HashtableEntrySetWrapper.class.getName() + "$HashtableIteratorWrapper");
      loadTerracottaClass(HashtableKeySetWrapper.class.getName());
      loadTerracottaClass(HashtableKeySetWrapper.class.getName() + "$IteratorWrapper");
      loadTerracottaClass(HashtableValuesWrapper.class.getName());
      loadTerracottaClass(HashtableValuesWrapper.class.getName() + "$IteratorWrapper");
      loadTerracottaClass(SetIteratorWrapper.class.getName());
      loadTerracottaClass(EnumerationWrapper.class.getName());
      loadTerracottaClass(NamedClassLoader.class.getName());
      loadTerracottaClass(NamedLoaderAdapter.class.getName());
      loadTerracottaClass(TransparentAccess.class.getName());
      loadTerracottaClass(BytecodeProvider.class.getName());
      loadTerracottaClass(ByteCodeUtil.class.getName());

      loadTerracottaClass(Manageable.class.getName());
      loadTerracottaClass(Clearable.class.getName());
      loadTerracottaClass(Manager.class.getName());
      loadTerracottaClass(InstrumentationLogger.class.getName());
      loadTerracottaClass(NullInstrumentationLogger.class.getName());
      loadTerracottaClass(NullManager.class.getName());
      loadTerracottaClass(ManagerUtil.class.getName());
      loadTerracottaClass(ManagerUtil.class.getName() + "$GlobalManagerHolder");
      loadTerracottaClass(TCObject.class.getName());
      loadTerracottaClass(ToggleableStrongReference.class.getName());
      loadTerracottaClass(TCClass.class.getName());
      loadTerracottaClass(TCField.class.getName());
      loadTerracottaClass(NullTCObject.class.getName());
      loadTerracottaClass(Cacheable.class.getName());
      loadTerracottaClass(ObjectID.class.getName());
      loadTerracottaClass(AbstractIdentifier.class.getName());
      loadTerracottaClass(TLinkable.class.getName());
      loadTerracottaClass(SessionsHelper.class.getName());
      loadTerracottaClass(GeronimoLoaderNaming.class.getName());
      loadTerracottaClass(JBossLoaderNaming.class.getName());
      loadTerracottaClass(WebsphereLoaderNaming.class.getName());
      loadTerracottaClass(TomcatLoaderNaming.class.getName());
      loadTerracottaClass(TCLogger.class.getName());
      loadTerracottaClass(Banner.class.getName());
      loadTerracottaClass(StandardClassProvider.class.getName());
      loadTerracottaClass(StandardClassProvider.SystemLoaderHolder.class.getName());
      loadTerracottaClass(Namespace.class.getName());
      loadTerracottaClass(ClassProcessorHelper.class.getName());
      loadTerracottaClass(ClassProcessorHelperJDK15.class.getName());
      loadTerracottaClass(ClassProcessorHelper.State.class.getName());
      loadTerracottaClass(ClassProcessorHelper.JarFilter.class.getName());
      loadTerracottaClass(ClassProcessor.class.getName());
      loadTerracottaClass(ClassPreProcessor.class.getName());
      loadTerracottaClass(ClassPostProcessor.class.getName());
      loadTerracottaClass(DSOSpringConfigHelper.class.getName());
      loadTerracottaClass(DSOContext.class.getName());
      loadTerracottaClass(ClassProvider.class.getName());
      loadTerracottaClass(TCRuntimeException.class.getName());
      loadTerracottaClass(FieldUtils.class.getName());
      loadTerracottaClass(UnsafeUtil.class.getName());
      loadTerracottaClass(TCNotSupportedMethodException.class.getName());
      loadTerracottaClass(ExceptionWrapper.class.getName());
      loadTerracottaClass(ExceptionWrapperImpl.class.getName());
      loadTerracottaClass(Os.class.getName());
      loadTerracottaClass(Util.class.getName());
      loadTerracottaClass(NIOWorkarounds.class.getName());
      loadTerracottaClass(TCProperties.class.getName());
      loadTerracottaClass(ClusterEventListener.class.getName());
      loadTerracottaClass(OverrideCheck.class.getName());
      loadTerracottaClass(JavaLangStringTC.class.getName());
      loadTerracottaClass(StringCompressionUtil.class.getName());
      loadTerracottaClass(CompressedData.class.getName());
      loadTerracottaClass(TCByteArrayOutputStream.class.getName());

      // These classes need to be specified as literal in order to prevent
      // the static block of IdentityWeakHashMap from executing during generating
      // the boot jar.
      loadTerracottaClass("com.tc.object.util.IdentityWeakHashMap");
      loadTerracottaClass("com.tc.object.util.IdentityWeakHashMap$TestKey");
      loadTerracottaClass("com.tc.object.bytecode.hook.impl.ArrayManager");
      loadTerracottaClass("com.tc.object.bytecode.NonDistributableObjectRegistry");
      loadTerracottaClass(ProxyInstance.class.getName());
      loadTerracottaClass(JavaLangArrayHelpers.class.getName());

      loadTerracottaClass(Vm.class.getName());
      loadTerracottaClass(VmVersion.class.getName());

      loadTerracottaClass(UnknownJvmVersionException.class.getName());
      loadTerracottaClass(UnknownRuntimeVersionException.class.getName());

      loadTerracottaClass(IBatisAccessPlanInstance.class.getName());
      loadTerracottaClass(HibernateProxyInstance.class.getName());

      loadTerracottaClass(ReflectiveProxy.class.getName());
      loadTerracottaClass(ReflectiveProxy.Handler.class.getName());
      loadTerracottaClass(PartitionManager.class.getName());

      addManagementClasses();

      addRuntimeClasses();

      addInstrumentedLogManager();
      addSunStandardLoaders();
      addInstrumentedAccessibleObject();
      addInstrumentedJavaLangThrowable();
      addInstrumentedJavaLangStringBuffer();
      addInstrumentedClassLoader();
      addInstrumentedJavaLangString();
      addInstrumentedJavaNetURL();
      addInstrumentedProxy();
      addTreeMap();

      addIBMSpecific();

      Map internalSpecs = getTCSpecs();
      loadBootJarClasses(removeAlreadyLoaded(massageSpecs(internalSpecs, true)));

      Map userSpecs = massageSpecs(getUserDefinedSpecs(internalSpecs), false);
      issueWarningsAndErrors();

      // user defined specs should ALWAYS be after internal specs
      loadBootJarClasses(removeAlreadyLoaded(userSpecs), true);

      // classes adapted/included after the user defined specs are not portable
      // if you want to make it portable, you will still need to declare
      // in the <additiona-boot-jar-classes/> section of your tc-config
      adaptClassIfNotAlreadyIncluded(BufferedWriter.class.getName(), BufferedWriterAdapter.class);
      adaptClassIfNotAlreadyIncluded(DataOutputStream.class.getName(), DataOutputStreamAdapter.class);
    } catch (Exception e) {
      exit(bootJarHandler.getCreationErrorMessage(), e);
    }

    try {
      bootJar.close();
      bootJarHandler.announceCreationEnd();
    } catch (IOException e) {
      exit(bootJarHandler.getCloseErrorMessage(), e);
    } catch (BootJarHandlerException e) {
      exit(e.getMessage(), e.getCause());
    }
  }

  private void addIBMSpecific() {
    if (Vm.isIBM()) {
      // Yes, the class name is misspelled
      adaptAndLoad("com.ibm.misc.SystemIntialization", new SystemInitializationAdapter());

      addIbmInstrumentedAtomicInteger();
      addIbmInstrumentedAtomicLong();
    }
  }

  private void addReflectionInstrumentation() {
    if (this.configHelper.reflectionEnabled()) {
      adaptAndLoad("java.lang.reflect.Field", new JavaLangReflectFieldAdapter());
      adaptAndLoad("java.lang.reflect.Array", new JavaLangReflectArrayAdapter());
    }
  }

  private final Map getAllSpecs() {
    Map map = new HashMap();
    TransparencyClassSpec[] allSpecs = configHelper.getAllSpecs();
    for (int i = 0; i < allSpecs.length; i++) {
      TransparencyClassSpec spec = allSpecs[i];
      map.put(spec.getClassName(), spec);
    }
    return Collections.unmodifiableMap(map);
  }

  private void loadClassIntoJar(String className, byte[] data, boolean isPreinstrumented) {
    loadClassIntoJar(className, data, isPreinstrumented, false);
  }

  private void loadClassIntoJar(String className, byte[] data, boolean isPreinstrumented, boolean isForeign) {
    Map userSpecs = getUserDefinedSpecs(getAllSpecs());
    if (!isForeign && userSpecs.containsKey(className)) consoleLogger
        .warn(className + " already belongs in the bootjar by default.");
    bootJar.loadClassIntoJar(className, data, isPreinstrumented, isForeign);
  }

  private void adaptAndLoad(String name, ClassAdapterFactory factory) {
    byte[] bytes = getSystemBytes(name);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = factory.create(cw, null);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    loadClassIntoJar(name, bytes, false);
  }

  private final void addManagementClasses() {
    loadTerracottaClass(SessionMonitorMBean.class.getName());
    loadTerracottaClass(SessionMonitorMBean.class.getName() + "$SessionsComptroller");
    loadTerracottaClass(TerracottaMBean.class.getName());
  }

  private final boolean shouldIncludeStringBufferAndFriends() {
    Map userSpecs = getUserDefinedSpecs(getTCSpecs());
    return userSpecs.containsKey("java.lang.StringBuffer") || userSpecs.containsKey("java.lang.AbstractStringBuilder")
           || userSpecs.containsKey("java.lang.StringBuilder");

  }

  private final void addRuntimeClasses() {
    loadTerracottaClass("com.tc.asm.AnnotationVisitor");
    loadTerracottaClass("com.tc.asm.AnnotationWriter");
    loadTerracottaClass("com.tc.asm.Attribute");
    loadTerracottaClass("com.tc.asm.ByteVector");
    loadTerracottaClass("com.tc.asm.ClassAdapter");
    loadTerracottaClass("com.tc.asm.ClassReader");
    loadTerracottaClass("com.tc.asm.ClassVisitor");
    loadTerracottaClass("com.tc.asm.ClassWriter");
    loadTerracottaClass("com.tc.asm.Edge");
    loadTerracottaClass("com.tc.asm.FieldVisitor");
    loadTerracottaClass("com.tc.asm.FieldWriter");
    loadTerracottaClass("com.tc.asm.Frame");
    loadTerracottaClass("com.tc.asm.Handler");
    loadTerracottaClass("com.tc.asm.Item");
    loadTerracottaClass("com.tc.asm.Label");
    loadTerracottaClass("com.tc.asm.MethodAdapter");
    loadTerracottaClass("com.tc.asm.MethodVisitor");
    loadTerracottaClass("com.tc.asm.MethodWriter");
    loadTerracottaClass("com.tc.asm.Opcodes");
    loadTerracottaClass("com.tc.asm.Type");

    loadTerracottaClass("com.tc.asm.signature.SignatureReader");
    loadTerracottaClass("com.tc.asm.signature.SignatureVisitor");
    loadTerracottaClass("com.tc.asm.signature.SignatureWriter");

    loadTerracottaClass("com.tc.asm.commons.EmptyVisitor");
    loadTerracottaClass("com.tc.asm.commons.SerialVersionUIDAdder");
    loadTerracottaClass("com.tc.asm.commons.SerialVersionUIDAdder$Item");

    // FIXME extract AW runtime classes
    loadTerracottaClass("com.tc.aspectwerkz.AspectContext");
    loadTerracottaClass("com.tc.aspectwerkz.DeploymentModel$PointcutControlledDeploymentModel");
    loadTerracottaClass("com.tc.aspectwerkz.DeploymentModel");
    loadTerracottaClass("com.tc.aspectwerkz.WeavedTestCase$WeaverTestRunner");
    loadTerracottaClass("com.tc.aspectwerkz.WeavedTestCase");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.After");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AfterFinally");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AfterReturning");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AfterThrowing");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AnnotationConstants");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AnnotationInfo");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Around");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AsmAnnotations");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Aspect");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.AspectAnnotationParser");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Before");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Expression");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Introduce");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.Mixin");
    loadTerracottaClass("com.tc.aspectwerkz.annotation.MixinAnnotationParser");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.AbstractAspectContainer");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.AbstractMixinFactory");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.AdviceInfo");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.AdviceType");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.Aspect");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.AspectContainer");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.DefaultAspectContainerStrategy");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.DefaultMixinFactory");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.MixinFactory");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.AbstractAspectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.Artifact");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.AspectFactoryManager");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.LazyPerXFactoryCompiler$PerClassAspectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.LazyPerXFactoryCompiler$PerThreadAspectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.LazyPerXFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.PerCflowXAspectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.PerJVMAspectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.PerObjectFactoryCompiler$PerInstanceFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.container.PerObjectFactoryCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.management.Aspects");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.management.HasInstanceLevelAspect");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.management.Mixins");
    loadTerracottaClass("com.tc.aspectwerkz.aspect.management.NoAspectBoundException");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.AbstractCflowSystemAspect$1");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.AbstractCflowSystemAspect$Cflow_sample");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.AbstractCflowSystemAspect");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.CflowAspectExpressionVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.CflowBinding");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.CflowCompiler$CompiledCflowAspect");
    loadTerracottaClass("com.tc.aspectwerkz.cflow.CflowCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.compiler.AspectWerkzC");
    loadTerracottaClass("com.tc.aspectwerkz.compiler.AspectWerkzCTask");
    loadTerracottaClass("com.tc.aspectwerkz.compiler.CompileException");
    loadTerracottaClass("com.tc.aspectwerkz.compiler.Utility");
    loadTerracottaClass("com.tc.aspectwerkz.compiler.VerifierClassLoader");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.Command");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.Invoker");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.RemoteProxy");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.RemoteProxyServer");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.RemoteProxyServerManager$1");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.RemoteProxyServerManager");
    loadTerracottaClass("com.tc.aspectwerkz.connectivity.RemoteProxyServerThread");
    loadTerracottaClass("com.tc.aspectwerkz.definition.AdviceDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.AspectDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.DefinitionParserHelper");
    loadTerracottaClass("com.tc.aspectwerkz.definition.DeploymentScope");
    loadTerracottaClass("com.tc.aspectwerkz.definition.DescriptorUtil");
    loadTerracottaClass("com.tc.aspectwerkz.definition.DocumentParser$PointcutInfo");
    loadTerracottaClass("com.tc.aspectwerkz.definition.DocumentParser");
    loadTerracottaClass("com.tc.aspectwerkz.definition.InterfaceIntroductionDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.MixinDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.Pointcut");
    loadTerracottaClass("com.tc.aspectwerkz.definition.PointcutDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.SystemDefinition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.SystemDefinitionContainer");
    loadTerracottaClass("com.tc.aspectwerkz.definition.Virtual");
    loadTerracottaClass("com.tc.aspectwerkz.definition.XmlParser$1");
    loadTerracottaClass("com.tc.aspectwerkz.definition.XmlParser");
    loadTerracottaClass("com.tc.aspectwerkz.definition.aspectj5.Definition$ConcreteAspect");
    loadTerracottaClass("com.tc.aspectwerkz.definition.aspectj5.Definition$Pointcut");
    loadTerracottaClass("com.tc.aspectwerkz.definition.aspectj5.Definition");
    loadTerracottaClass("com.tc.aspectwerkz.definition.aspectj5.DocumentParser");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.AdviceDefinitionBuilder");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.AspectDefinitionBuilder");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.AspectModule");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.AspectModuleDeployer");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.DefinitionBuilder");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.MixinDefinitionBuilder");
    loadTerracottaClass("com.tc.aspectwerkz.definition.deployer.PointcutDefinitionBuilder");
    loadTerracottaClass("com.tc.aspectwerkz.env.EnvironmentDetector");
    loadTerracottaClass("com.tc.aspectwerkz.exception.DefinitionException");
    loadTerracottaClass("com.tc.aspectwerkz.exception.DefinitionNotFoundException");
    loadTerracottaClass("com.tc.aspectwerkz.exception.ExpressionException");
    loadTerracottaClass("com.tc.aspectwerkz.exception.WrappedRuntimeException");
    loadTerracottaClass("com.tc.aspectwerkz.expression.AdvisedClassFilterExpressionVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ArgsIndexVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.DumpVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionContext");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionException");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionInfo");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionNamespace");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionValidateVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ExpressionVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.PointcutType");
    loadTerracottaClass("com.tc.aspectwerkz.expression.SubtypePatternType");
    loadTerracottaClass("com.tc.aspectwerkz.expression.Undeterministic");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTAnd");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTArgParameter");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTArgs");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTAttribute");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTCall");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTCflow");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTCflowBelow");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTClassPattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTConstructorPattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTExecution");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTExpression");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTFieldPattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTGet");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTHandler");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTHasField");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTHasMethod");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTIf");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTMethodPattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTModifier");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTNot");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTOr");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTParameter");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTPointcutReference");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTRoot");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTSet");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTStaticInitialization");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTTarget");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTThis");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTWithin");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ASTWithinCode");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParser$JJCalls");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParser$LookaheadSuccess");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParser");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParserConstants");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParserTokenManager");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParserTreeConstants");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ExpressionParserVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.JJTExpressionParserState");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.Node");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.ParseException");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.SimpleCharStream");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.SimpleNode");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.Token");
    loadTerracottaClass("com.tc.aspectwerkz.expression.ast.TokenMgrError");
    loadTerracottaClass("com.tc.aspectwerkz.expression.regexp.NamePattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.regexp.Pattern");
    loadTerracottaClass("com.tc.aspectwerkz.expression.regexp.TypePattern");
    loadTerracottaClass("com.tc.aspectwerkz.hook.AbstractStarter");
    loadTerracottaClass("com.tc.aspectwerkz.hook.BootClasspathStarter");
    loadTerracottaClass("com.tc.aspectwerkz.hook.ClassLoaderPatcher");
    loadTerracottaClass("com.tc.aspectwerkz.hook.ClassLoaderPreProcessor");
    loadTerracottaClass("com.tc.aspectwerkz.hook.ClassPreProcessor");
    loadTerracottaClass("com.tc.aspectwerkz.hook.StreamRedirectThread");
    loadTerracottaClass("com.tc.aspectwerkz.hook.impl.ClassPreProcessorHelper");
    loadTerracottaClass("com.tc.aspectwerkz.hook.impl.StdoutPreProcessor");
    loadTerracottaClass("com.tc.aspectwerkz.hook.impl.WeavingClassLoader");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.Advice");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.Advisable");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.AdvisableImpl");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.AfterAdvice");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.AfterReturningAdvice");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.AfterThrowingAdvice");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.AroundAdvice");
    loadTerracottaClass("com.tc.aspectwerkz.intercept.BeforeAdvice");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.CatchClauseRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.CatchClauseSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.CodeRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.CodeSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.ConstructorRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.ConstructorSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.EnclosingStaticJoinPoint");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.FieldRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.FieldSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.JoinPoint");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.MemberRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.MemberSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.MethodRtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.MethodSignature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.Rtti");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.Signature");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.StaticJoinPoint");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.CatchClauseRttiImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.CatchClauseSignatureImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.ConstructorRttiImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.ConstructorSignatureImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.EnclosingStaticJoinPointImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.FieldRttiImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.FieldSignatureImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.MethodRttiImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.MethodSignatureImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.MethodTuple");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.StaticInitializationRttiImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.impl.StaticInitializerSignatureImpl");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.management.AdviceInfoContainer");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.management.JoinPointManager$CompiledJoinPoint");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.management.JoinPointManager");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.management.JoinPointType");
    loadTerracottaClass("com.tc.aspectwerkz.joinpoint.management.SignatureFactory");
    loadTerracottaClass("com.tc.aspectwerkz.perx.PerObjectAspect");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ClassBytecodeRepository");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.Proxy");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyCompilerHelper$1");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyCompilerHelper");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyDelegationCompiler$ProxyCompilerClassVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyDelegationCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyDelegationStrategy$CompositeClassKey");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxyDelegationStrategy");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxySubclassingCompiler$ProxyCompilerClassVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxySubclassingCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.ProxySubclassingStrategy");
    loadTerracottaClass("com.tc.aspectwerkz.proxy.Uuid");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.CflowMetaData");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.NullClassInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ClassInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ClassInfoHelper");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ClassInfoRepository");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ClassList");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ConstructorInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.FieldInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.MemberInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.MetaDataInspector");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.MethodComparator");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.MethodInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ReflectHelper");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.ReflectionInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.StaticInitializationInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.StaticInitializationInfoImpl");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.TypeConverter");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo$ClassInfoClassAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo$MethodParameterNamesCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfoRepository");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmConstructorInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmFieldInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmMemberInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.AsmMethodInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.FieldStruct");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.MemberStruct");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.asm.MethodStruct");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaClassInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaClassInfoRepository");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaConstructorInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaFieldInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaMemberInfo");
    loadTerracottaClass("com.tc.aspectwerkz.reflect.impl.java.JavaMethodInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.AspectWerkzPreProcessor$Output");
    loadTerracottaClass("com.tc.aspectwerkz.transform.AspectWerkzPreProcessor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.ByteArray");
    loadTerracottaClass("com.tc.aspectwerkz.transform.ClassCacheTuple");
    loadTerracottaClass("com.tc.aspectwerkz.transform.InstrumentationContext");
    loadTerracottaClass("com.tc.aspectwerkz.transform.JoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.Properties");
    loadTerracottaClass("com.tc.aspectwerkz.transform.TransformationConstants");
    loadTerracottaClass("com.tc.aspectwerkz.transform.TransformationUtil");
    loadTerracottaClass("com.tc.aspectwerkz.transform.WeavingStrategy");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AdviceMethodInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmCopyAdapter$CopyAnnotationAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmCopyAdapter$CopyMethodAnnotationElseNullAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmCopyAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmHelper$1");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmHelper$2");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmHelper");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmNullAdapter$NullAnnotationVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmNullAdapter$NullClassAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmNullAdapter$NullFieldAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmNullAdapter$NullMethodAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AsmNullAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AspectInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.AspectModelManager");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.EmittedJoinPoint");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.ProxyWeavingStrategy");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.AbstractJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.CompilationInfo$Model");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.CompilationInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.CompilerHelper");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.CompilerInput");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.ConstructorCallJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.ConstructorCallJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.ConstructorExecutionJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.ConstructorExecutionJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.FieldGetJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.FieldGetJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.FieldSetJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.FieldSetJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.HandlerJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.HandlerJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.MatchingJoinPointInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.MethodCallJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.MethodCallJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.MethodExecutionJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.MethodExecutionJoinPointRedefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.RuntimeCheckVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.compiler.StaticInitializationJoinPointCompiler");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.ChangeSet$Element");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.ChangeSet");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.Deployer");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.DeploymentHandle$DefinitionChangeElement");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.DeploymentHandle");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.Redefiner");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.RedefinerFactory$Type");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.deployer.RedefinerFactory");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.model.AopAllianceAspectModel");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.model.AspectWerkzAspectModel$CustomProceedMethodStruct");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.model.AspectWerkzAspectModel");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.model.SpringAspectModel");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.spi.AspectModel$AroundClosureClassInfo$Type");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.spi.AspectModel$AroundClosureClassInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.spi.AspectModel");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddInterfaceVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddMixinMethodsVisitor$AppendToInitMethodCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddMixinMethodsVisitor$MixinFieldInfo");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddMixinMethodsVisitor$PrependToClinitMethodCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddMixinMethodsVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AddWrapperVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AfterObjectInitializationCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AlreadyAddedMethodAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.AlreadyAddedMethodVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorBodyVisitor$DispatchCtorBodyCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorBodyVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor$LookaheadNewDupInvokeSpecialInstructionClassAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor$LookaheadNewDupInvokeSpecialInstructionCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor$NewInvocationStruct");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor$ReplaceNewInstructionCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.FieldSetFieldGetVisitor$ReplacePutFieldAndGetFieldInstructionCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.FieldSetFieldGetVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor$CatchClauseCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor$CatchLabelStruct");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor$LookaheadCatchLabelsClassAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor$LookaheadCatchLabelsMethodAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.InstanceLevelAspectVisitor$AppendToInitMethodCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.InstanceLevelAspectVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.JoinPointInitVisitor$InsertBeforeClinitCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.JoinPointInitVisitor$InsertBeforeInitJoinPointsCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.JoinPointInitVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.LabelToLineNumberVisitor$1");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.LabelToLineNumberVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.MethodCallVisitor$ReplaceInvokeInstructionCodeAdapter");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.MethodCallVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.MethodExecutionVisitor$1");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.MethodExecutionVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.SerialVersionUidVisitor$Add");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.SerialVersionUidVisitor$FieldItem");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.SerialVersionUidVisitor$Item");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.SerialVersionUidVisitor$MethodItem");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.SerialVersionUidVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.transform.inlining.weaver.StaticInitializationVisitor");
    loadTerracottaClass("com.tc.aspectwerkz.util.ContextClassLoader");
    loadTerracottaClass("com.tc.aspectwerkz.util.EnvironmentDetect");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap$1");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap$2");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap$3");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap$Entry");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap$OrderedIterator");
    loadTerracottaClass("com.tc.aspectwerkz.util.SequencedHashMap");
    loadTerracottaClass("com.tc.aspectwerkz.util.SerializableThreadLocal");
    loadTerracottaClass("com.tc.aspectwerkz.util.Strings");
    loadTerracottaClass("com.tc.aspectwerkz.util.Util");
    loadTerracottaClass("com.tc.aspectwerkz.util.UuidGenerator");

    loadTerracottaClass("com.tc.backport175.Annotation");
    loadTerracottaClass("com.tc.backport175.Annotations");
    loadTerracottaClass("com.tc.backport175.ReaderException");

    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationDefaults");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationDefaults$AnnotationDefaultsClassVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationDefaults$AnnotationDefaultsMethodVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationDefaults$DefaultAnnotationBuilderVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$Annotation");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$Array");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$Enum");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$NamedValue");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$NestedAnnotationElement");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationElement$Type");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$AnnotationBuilderVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$AnnotationRetrievingConstructorVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$AnnotationRetrievingFieldVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$AnnotationRetrievingMethodVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$AnnotationRetrievingVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$ClassKey");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$MemberKey");
    loadTerracottaClass("com.tc.backport175.bytecode.AnnotationReader$TraceAnnotationVisitor");
    loadTerracottaClass("com.tc.backport175.bytecode.DefaultBytecodeProvider");
    loadTerracottaClass("com.tc.backport175.bytecode.SignatureHelper");

    loadTerracottaClass("com.tc.backport175.bytecode.spi.BytecodeProvider");

    loadTerracottaClass("com.tc.backport175.proxy.JavaDocAnnotationInvocationHander");
    loadTerracottaClass("com.tc.backport175.proxy.ProxyFactory");
    loadTerracottaClass("com.tc.backport175.proxy.ResolveAnnotationException");

    // FIXME remove unused stuff and move to com.tc. namespace
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$IChangedListener");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$IState");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$IStateChangedListener");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$IStateVisitedListener");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$ITransitionVisitedListener");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$LinkedSet_State");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$State$Transition");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$State");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton$Wrapper_State");
    loadTerracottaClass("com.tc.jrexx.automaton.Automaton");
    loadTerracottaClass("com.tc.jrexx.automaton.IProperties");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$IPState");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$LinkedSet_PState");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$PProperties");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$PState");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_GroupBegin");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_GroupEnd");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_LABEL");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_LITERAL");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_LITERALSET");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_RegExp");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern$TerminalFormat_REPETITION");
    loadTerracottaClass("com.tc.jrexx.regex.Automaton_Pattern");
    loadTerracottaClass("com.tc.jrexx.regex.InvalidExpression");
    loadTerracottaClass("com.tc.jrexx.regex.ParseException");
    loadTerracottaClass("com.tc.jrexx.regex.Pattern");
    loadTerracottaClass("com.tc.jrexx.regex.PatternPro");
    loadTerracottaClass("com.tc.jrexx.regex.PAutomaton$1");
    loadTerracottaClass("com.tc.jrexx.regex.PAutomaton$2");
    loadTerracottaClass("com.tc.jrexx.regex.PAutomaton");
    loadTerracottaClass("com.tc.jrexx.regex.PScanner");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_AndOp");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_EOF");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_GroupBegin");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_GroupEnd");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_NotOp");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_OrOp");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_RegExp");
    loadTerracottaClass("com.tc.jrexx.regex.Terminal_Repetition");

    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$EClosureSet");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$Tupel");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$EClosure");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$Transition");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$ISState");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$ISStateChangedListener");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$LinkedSet_SState");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$SProperties");
    loadTerracottaClass("com.tc.jrexx.set.AutomatonSet_String$SState");
    loadTerracottaClass("com.tc.jrexx.set.CharSet");
    loadTerracottaClass("com.tc.jrexx.set.CharSet$IAbstract");
    loadTerracottaClass("com.tc.jrexx.set.CharSet$LongMap");
    loadTerracottaClass("com.tc.jrexx.set.CharSet$LongMapIterator");
    loadTerracottaClass("com.tc.jrexx.set.CharSet$Wrapper");
    loadTerracottaClass("com.tc.jrexx.set.DFASet");
    loadTerracottaClass("com.tc.jrexx.set.DFASet$State");
    loadTerracottaClass("com.tc.jrexx.set.DFASet$State$Transition");
    loadTerracottaClass("com.tc.jrexx.set.FSAData");
    loadTerracottaClass("com.tc.jrexx.set.FSAData$State");
    loadTerracottaClass("com.tc.jrexx.set.FSAData$State$Transition");
    loadTerracottaClass("com.tc.jrexx.set.ISet_char");
    loadTerracottaClass("com.tc.jrexx.set.ISet_char$Iterator");
    loadTerracottaClass("com.tc.jrexx.set.IState");
    loadTerracottaClass("com.tc.jrexx.set.IStatePro");
    loadTerracottaClass("com.tc.jrexx.set.IStatePro$IChangeListener");
    loadTerracottaClass("com.tc.jrexx.set.IStatePro$ITransition");
    loadTerracottaClass("com.tc.jrexx.set.IStatePro$IVisitListener");

    loadTerracottaClass("com.tc.jrexx.set.SAutomaton");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$IChangeListener");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$SAutomatonChangeListener");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$State");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$StatePro");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$StateProChangedListener");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$StateProVisitedListener");
    loadTerracottaClass("com.tc.jrexx.set.SAutomaton$Transition");

    loadTerracottaClass("com.tc.jrexx.set.SAutomatonData");
    loadTerracottaClass("com.tc.jrexx.set.SAutomatonData$State");
    loadTerracottaClass("com.tc.jrexx.set.SAutomatonData$State$Transition");
    loadTerracottaClass("com.tc.jrexx.set.StateProSet");
    loadTerracottaClass("com.tc.jrexx.set.StateProSet$Iterator");
    loadTerracottaClass("com.tc.jrexx.set.StateProSet$Wrapper_State");
    loadTerracottaClass("com.tc.jrexx.set.XML");

    loadTerracottaClass("com.tc.object.applicator.TCURL");
    loadTerracottaClass("com.tc.object.bytecode.TCMapEntry");

    // DEV-116; Some of these probably should'nt be in the boot jar
    loadTerracottaClass("com.tc.exception.ImplementMe");
    loadTerracottaClass("com.tc.exception.TCClassNotFoundException");
    loadTerracottaClass("com.tc.object.dna.api.DNAException");
    loadTerracottaClass("com.tc.util.Assert");
    loadTerracottaClass("com.tc.util.StringUtil");
    loadTerracottaClass("com.tc.util.TCAssertionError");

    // this class needed for ibm-jdk-15 branch
    loadTerracottaClass("com.tc.object.bytecode.ClassAdapterFactory");
  }

  private final void addTreeMap() {
    String className = "java.util.TreeMap";
    byte[] orig = getSystemBytes(className);

    TransparencyClassSpec spec = configHelper.getSpec(className);

    byte[] transformed = doDSOTransform(className, orig);

    ClassReader cr = new ClassReader(transformed);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new JavaUtilTreeMapAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar(className, cw.toByteArray(), spec.isPreInstrumented());
  }

  private final void issueWarningsAndErrors() {
    issueErrors(nonExistingClasses, "could not be found", "remove or correct",
                "Attempt to add classes that cannot be found: ");
    issueErrors(notBootstrapClasses,
                "are not loaded by the bootstap classloader and have not been included in the boot jar", "remove",
                "Attempt to add classes that are not loaded by bootstrap classloader: ");
    issueErrors(notAdaptableClasses, "are non-adaptable types and have not been included in the boot jar", "remove",
                "Attempt to add non-adaptable classes: ");
    issueErrors(logicalSubclasses,
                "are subclasses of logically managed types and have not been included in the boot jar", "remove",
                "Attempt to add subclasses of logically manages classes: ");
    issueWarnings(autoIncludedBootstrapClasses,
                  "were automatically included in the boot jar since they are required super classes", "add");
  }

  private final void issueErrors(Set classes, String desc, String verb, String shortDesc) {
    if (!classes.isEmpty()) {
      Banner.errorBanner("Boot jar creation failed.  The following set of classes " + desc + ". Please " + verb
                         + " them in the <additional-boot-jar-classes> section of the terracotta config: " + classes);
      exit(shortDesc + classes, null);
    }
  }

  private final void issueWarnings(Set classes, String desc, String verb) {
    if (!classes.isEmpty()) {
      Banner.warnBanner("The following set of classes " + desc + ". Please " + verb
                        + " them in the <additional-boot-jar-classes> section of the terracotta config: " + classes);
    }
  }

  private final Map removeAlreadyLoaded(Map specs) {
    Map rv = new HashMap(specs);
    for (Iterator i = rv.keySet().iterator(); i.hasNext();) {
      String className = (String) i.next();
      if (bootJar.classLoaded(className)) {
        i.remove();
      }
    }

    return Collections.unmodifiableMap(rv);
  }

  private final Map massageSpecs(Map specs, boolean tcSpecs) {
    Map rv = new HashMap();

    for (Iterator i = specs.values().iterator(); i.hasNext();) {
      final TransparencyClassSpec spec = (TransparencyClassSpec) i.next();

      final Class topClass = getBootstrapClass(spec.getClassName());
      if (topClass == null) {
        if (tcSpecs && !spec.isHonorJDKSubVersionSpecific()) throw new AssertionError("Class not found: "
                                                                                      + spec.getClassName());
        if (!tcSpecs) {
          nonExistingClasses.add(spec.getClassName());
        }
        continue;
      } else if (topClass.getClassLoader() != null) {
        if (!tcSpecs) {
          notBootstrapClasses.add(topClass.getName());
          continue;
        }
      }

      Set supers = new HashSet();
      boolean add = true;
      if (!configHelper.isLogical(topClass.getName())) {
        Class clazz = topClass;
        while ((clazz != null) && (!portability.isInstrumentationNotNeeded(clazz.getName()))) {
          if (configHelper.isNeverAdaptable(JavaClassInfo.getClassInfo(clazz))) {
            if (tcSpecs) { throw new AssertionError("Not adaptable: " + clazz); }

            add = false;
            notAdaptableClasses.add(topClass.getName());
            break;
          }

          if ((clazz != topClass) && configHelper.isLogical(clazz.getName())) {
            if (tcSpecs) { throw new AssertionError(topClass + " is subclass of logical type " + clazz.getName()); }

            add = false;
            logicalSubclasses.add(topClass.getName());
            break;
          }

          if (!specs.containsKey(clazz.getName()) && ((bootJar == null) || !bootJar.classLoaded(clazz.getName()))) {
            if (tcSpecs) { throw new AssertionError("Missing super class " + clazz.getName() + " for type "
                                                    + spec.getClassName()); }
            supers.add(clazz.getName());
          }

          clazz = clazz.getSuperclass();
        }
      }

      if (add) {
        // include orignal class
        rv.put(topClass.getName(), spec);

        // include supers (if found)
        for (Iterator supes = supers.iterator(); supes.hasNext();) {
          String name = (String) supes.next();
          autoIncludedBootstrapClasses.add(name);
          TransparencyClassSpec superSpec = configHelper.getOrCreateSpec(name);
          superSpec.markPreInstrumented();
          rv.put(name, superSpec);
        }
      }
    }

    return Collections.unmodifiableMap(rv);
  }

  private final static Class getBootstrapClass(String className) {
    try {
      return Class.forName(className, false, ClassLoader.getSystemClassLoader());
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private final void loadBootJarClasses(Map specs, boolean foreignClass) {
    for (Iterator iter = specs.values().iterator(); iter.hasNext();) {
      TransparencyClassSpec spec = (TransparencyClassSpec) iter.next();
      if (foreignClass) spec.markForeign();
      announce("Adapting: " + spec.getClassName());
      byte[] classBytes = doDSOTransform(spec.getClassName(), getSystemBytes(spec.getClassName()));
      loadClassIntoJar(spec.getClassName(), classBytes, spec.isPreInstrumented(), foreignClass);
    }
  }

  private final void loadBootJarClasses(Map specs) {
    loadBootJarClasses(specs, false);
  }

  private final Map getTCSpecs() {
    Map map = new HashMap();

    TransparencyClassSpec[] allSpecs = configHelper.getAllSpecs();
    for (int i = 0; i < allSpecs.length; i++) {
      TransparencyClassSpec spec = allSpecs[i];
      if (spec.isPreInstrumented()) {
        map.put(spec.getClassName(), spec);
      }
    }

    return Collections.unmodifiableMap(map);
  }

  private final Map getUserDefinedSpecs(Map internalSpecs) {
    Map rv = new HashMap();
    for (Iterator i = configHelper.getAllUserDefinedBootSpecs(); i.hasNext();) {
      TransparencyClassSpec spec = (TransparencyClassSpec) i.next();
      Assert.assertTrue(spec.isPreInstrumented());

      // Take out classes that don't need instrumentation (but the user included anyway)
      if (!portability.isInstrumentationNotNeeded(spec.getClassName())) {
        rv.put(spec.getClassName(), spec);
      }
    }
    // substract TC specs from the user set (overlaps are bad)
    rv.keySet().removeAll(internalSpecs.keySet());
    return Collections.unmodifiableMap(rv);
  }

  private final void loadTerracottaClass(String className) {
    loadClassIntoJar(className, getTerracottaBytes(className), false);
  }

  private final byte[] getTerracottaBytes(String className) {
    return getBytes(className, tcLoader);
  }

  private final byte[] getSystemBytes(String className) {
    return getBytes(className, systemLoader);
  }

  private final byte[] getBytes(String className, ClassLoader provider) {
    try {
      return getBytesForClass(className, provider);
    } catch (ClassNotFoundException e) {
      throw exit("Error sourcing bytes for class " + className, e);
    }
  }

  public static final byte[] getBytesForClass(String className, ClassLoader loader) throws ClassNotFoundException {
    String resource = BootJar.classNameToFileName(className);
    final InputStream is = loader.getResourceAsStream(resource);
    if (is == null) { throw new ClassNotFoundException("No resource found for class: " + className); }
    try {
      return getBytesForClass(is);
    } catch (IOException e) {
      throw new ClassNotFoundException("Error reading bytes for " + resource, e);
    }
  }
  
  public static final byte[] getBytesForClass(final InputStream is) throws IOException {
    final int size = 4096;
    byte[] buffer = new byte[size];
    ByteArrayOutputStream baos = new ByteArrayOutputStream(size);

    int read;
    try {
      while ((read = is.read(buffer, 0, size)) > 0) {
        baos.write(buffer, 0, read);
      }
    } finally {
      try {
        is.close();
      } catch (IOException ioe) {
        // ignore
      }
    }

    return baos.toByteArray();
  }

  private final RuntimeException exit(String msg, Throwable t) {
    if (!WRITE_OUT_TEMP_FILE) {
      bootJar.setCreationErrorOccurred(true);
    }

    if (bootJar != null) {
      try {
        bootJar.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    if (t != null) {
      t.printStackTrace(pw);
    }

    if (msg != null) {
      pw.println("\n*************** ERROR ***************\n* " + msg + "\n*************************************");
    }

    String output = sw.toString();
    if (output.length() == 0) {
      new Throwable("Unspecified error creating boot jar").printStackTrace(pw);
      output = sw.toString();
    }

    System.err.println(output);
    System.err.flush();
    System.exit(1);

    return new RuntimeException("VM Should have exited");
  }

  private final void addInstrumentedJavaLangStringBuffer() {
    boolean makePortable = shouldIncludeStringBufferAndFriends();

    if (makePortable) {
      addPortableStringBuffer();
    } else {
      addNonPortableStringBuffer();
    }
  }

  private void addInstrumentedAccessibleObject() {
    String classname = AccessibleObject.class.getName();
    byte[] bytes = getSystemBytes(classname);

    // instrument the state changing methods in AccessibleObject
    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new AccessibleObjectAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // regular DSO instrumentation
    TransparencyClassSpec spec = configHelper.getOrCreateSpec(classname);
    spec.markPreInstrumented();

    loadClassIntoJar(spec.getClassName(), bytes, spec.isPreInstrumented());
  }

  private void addIbmInstrumentedAtomicInteger() {
    Vm.assertIsIbm();
    if (!Vm.isJDK15Compliant()) { return; }

    String classname = "java.util.concurrent.atomic.AtomicInteger";
    byte[] bytes = getSystemBytes(classname);

    // instrument the state changing methods in AtomicInteger
    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new AtomicIntegerAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // regular DSO instrumentation
    TransparencyClassSpec spec = configHelper.getOrCreateSpec(classname);
    spec.markPreInstrumented();

    loadClassIntoJar(spec.getClassName(), bytes, spec.isPreInstrumented());
  }

  private void addIbmInstrumentedAtomicLong() {
    Vm.assertIsIbm();
    if (!Vm.isJDK15Compliant()) { return; }

    String classname = "java.util.concurrent.atomic.AtomicLong";
    byte[] bytes = getSystemBytes(classname);

    // instrument the state changing methods in AtomicLong
    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new AtomicLongAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // regular DSO instrumentation
    TransparencyClassSpec spec = configHelper.getOrCreateSpec(classname);
    spec.markPreInstrumented();

    loadClassIntoJar(spec.getClassName(), bytes, spec.isPreInstrumented());
  }

  private final void addPortableStringBuffer() {
    boolean isJDK15 = Vm.isJDK15Compliant();
    if (isJDK15) {
      addAbstractStringBuilder();
    }

    byte[] bytes = getSystemBytes("java.lang.StringBuffer");

    // 1st pass
    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new StringBufferAdapter(cw, Vm.VERSION);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // 2nd pass
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new DuplicateMethodAdapter(cw, Collections.singleton("getChars(II[CI)V"));
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // 3rd pass (regular DSO instrumentation)
    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.lang.StringBuffer");
    spec.markPreInstrumented();
    configHelper.addWriteAutolock("* java.lang.StringBuffer.*(..)");
    bytes = doDSOTransform(spec.getClassName(), bytes);

    // 4th pass (String.getChars(..) calls)
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new StringGetCharsAdapter(cw, new String[] { "^" + DuplicateMethodAdapter.UNMANAGED_PREFIX + ".*" });
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    // 5th pass (fixups)
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new StringBufferAdapter.FixUp(cw, Vm.VERSION);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    loadClassIntoJar(spec.getClassName(), bytes, spec.isPreInstrumented());
  }

  private final void addNonPortableStringBuffer() {
    // even if we aren't making StringBu[ild|ff]er portable, we still need to make
    // sure it calls the fast getChars() methods on String

    boolean isJDK15 = Vm.isJDK15Compliant();

    if (isJDK15) {
      if (Vm.isIBM()) {
        addNonPortableStringBuffer("java.lang.StringBuilder");
      } else {
        addNonPortableStringBuffer("java.lang.AbstractStringBuilder");
      }
    }

    addNonPortableStringBuffer("java.lang.StringBuffer");
  }

  private void addNonPortableStringBuffer(String className) {
    TransparencyClassSpec spec = configHelper.getOrCreateSpec(className);
    spec.markPreInstrumented();

    byte[] bytes = getSystemBytes(className);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new StringGetCharsAdapter(cw, new String[] { ".*" });
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    bytes = cw.toByteArray();

    loadClassIntoJar(className, bytes, spec.isPreInstrumented());
  }

  private final void addAbstractStringBuilder() {
    String className;
    if (Vm.isIBM()) {
      className = "java.lang.StringBuilder";
    } else {
      className = "java.lang.AbstractStringBuilder";
    }

    byte[] classBytes = getSystemBytes(className);

    ClassReader cr = new ClassReader(classBytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new DuplicateMethodAdapter(cw, Collections.singleton("getChars(II[CI)V"));
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    classBytes = cw.toByteArray();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec(className);
    spec.markPreInstrumented();

    classBytes = doDSOTransform(className, classBytes);

    cr = new ClassReader(classBytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new AbstractStringBuilderAdapter(cw, className);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    cr = new ClassReader(cw.toByteArray());
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new StringGetCharsAdapter(cw, new String[] { "^" + DuplicateMethodAdapter.UNMANAGED_PREFIX + ".*" });
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar(className, cw.toByteArray(), spec.isPreInstrumented());
  }

  private final void addInstrumentedProxy() {
    String className = "java.lang.reflect.Proxy";
    byte[] bytes = getSystemBytes(className);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new JavaLangReflectProxyClassAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec(className);
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar(className, bytes, true);
  }

  private final void addInstrumentedLogManager() {
    String className = "java.util.logging.LogManager";
    byte[] bytes = getSystemBytes(className);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new LogManagerAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    loadClassIntoJar(className, bytes, false);
  }

  private final void addInstrumentedJavaLangString() {
    byte[] orig = getSystemBytes("java.lang.String");

    ClassReader cr = new ClassReader(orig);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new JavaLangStringAdapter(cw, Vm.VERSION, shouldIncludeStringBufferAndFriends(), Vm.isAzul(), Vm
        .isIBM());
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar("java.lang.String", cw.toByteArray(), false);
  }

  private final void addInstrumentedJavaNetURL() {
    String className = "java.net.URL";
    byte[] bytes = getSystemBytes(className);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new JavaNetURLAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    TransparencyClassSpec spec = configHelper.getOrCreateSpec(className, "com.tc.object.applicator.URLApplicator");
    spec.markPreInstrumented();
    spec.setHonorTransient(true);
    spec.addAlwaysLogSpec(SerializationUtil.URL_SET_SIGNATURE);
    // note that there's another set method, that is actually never referenced
    // from URLStreamHandler, so it's not accessible from classes that extend
    // URLStreamHandler, so I'm not supporting it here

    bytes = doDSOTransform(className, cw.toByteArray());

    loadClassIntoJar(className, bytes, spec.isPreInstrumented());
  }

  private final void addSunStandardLoaders() {
    byte[] orig = getSystemBytes("sun.misc.Launcher$AppClassLoader");
    ClassReader cr = new ClassReader(orig);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new StandardClassLoaderAdapter(cw, Namespace.getStandardSystemLoaderName(),
                                                     SYSTEM_CLASSLOADER_NAME_PROPERTY);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    loadClassIntoJar("sun.misc.Launcher$AppClassLoader", cw.toByteArray(), false);

    orig = getSystemBytes("sun.misc.Launcher$ExtClassLoader");
    cr = new ClassReader(orig);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new StandardClassLoaderAdapter(cw, Namespace.getStandardExtensionsLoaderName(), EXT_CLASSLOADER_NAME_PROPERTY);
    cr.accept(cv, ClassReader.SKIP_FRAMES);
    loadClassIntoJar("sun.misc.Launcher$ExtClassLoader", cw.toByteArray(), false);
  }

  private final void addInstrumentedJavaLangThrowable() {
    String className = "java.lang.Throwable";
    byte[] bytes = getSystemBytes(className);

    if (System.getProperty(TC_DEBUG_THROWABLE_CONSTRUCTION) != null) {
      ClassReader cr = new ClassReader(bytes);
      ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
      ClassVisitor cv = new JavaLangThrowableDebugClassAdapter(cw);
      cr.accept(cv, ClassReader.SKIP_FRAMES);
      bytes = cw.toByteArray();
    }

    TransparencyClassSpec spec = configHelper.getOrCreateSpec(className);
    spec.markPreInstrumented();
    spec.setHonorTransient(true);

    byte[] instrumented = doDSOTransform(className, bytes);

    loadClassIntoJar(className, instrumented, spec.isPreInstrumented());
  }

  /**
   * This instrumentation is temporary to add debug statements to the CyclicBarrier class.
   */
  private final void addInstrumentedJavaUtilConcurrentCyclicBarrier() {
    if (!Vm.isJDK15Compliant()) { return; }

    byte[] bytes = getSystemBytes("java.util.concurrent.CyclicBarrier");

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new JavaUtilConcurrentCyclicBarrierDebugClassAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.util.concurrent.CyclicBarrier");
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.CyclicBarrier", bytes, true);
  }

  private final void addInstrumentedJavaUtilConcurrentHashMap() {
    if (!Vm.isJDK15Compliant()) { return; }

    loadTerracottaClass("com.tcclient.util.ConcurrentHashMapEntrySetWrapper");
    loadTerracottaClass("com.tcclient.util.ConcurrentHashMapEntrySetWrapper$IteratorWrapper");

    // java.util.concurrent.ConcurrentHashMap
    String jClassNameDots = "java.util.concurrent.ConcurrentHashMap";
    String tcClassNameDots = "java.util.concurrent.ConcurrentHashMapTC";

    byte[] tcData = getSystemBytes(tcClassNameDots);
    ClassReader tcCR = new ClassReader(tcData);
    ClassNode tcCN = new ClassNode();
    tcCR.accept(tcCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    byte[] jData = getSystemBytes(jClassNameDots);
    ClassReader jCR = new ClassReader(jData);
    ClassWriter cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv1 = new JavaUtilConcurrentHashMapAdapter(cw);

    jCR.accept(cv1, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();

    jCR = new ClassReader(jData);
    cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);

    ClassInfo jClassInfo = AsmClassInfo.getClassInfo(jClassNameDots, systemLoader);
    TransparencyClassAdapter dsoAdapter = configHelper
        .createDsoClassAdapterFor(cw, jClassInfo, instrumentationLogger, getClass().getClassLoader(), true, true);
    Map instrumentedContext = new HashMap();
    ClassVisitor cv = new SerialVersionUIDAdder(new MergeTCToJavaClassAdapter(cw, dsoAdapter, jClassNameDots,
                                                                              tcClassNameDots, tcCN,
                                                                              instrumentedContext));
    jCR.accept(cv, ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();
    jData = doDSOTransform(jClassNameDots, jData);
    loadClassIntoJar(jClassNameDots, jData, true);

    // java.util.concurrent.ConcurrentHashMap$HashEntry
    byte[] bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$HashEntry");
    ClassReader cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentHashMapHashEntryAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$HashEntry");
    spec.addDoNotInstrument(TCMapEntry.TC_RAWSETVALUE_METHOD_NAME);
    spec.addDoNotInstrument(TCMapEntry.TC_ISVALUEFAULTEDIN_METHOD_NAME);
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    spec.setCallConstructorOnLoad(true);
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$HashEntry", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$Segment
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$Segment");
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentHashMapSegmentAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$Segment");
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    spec.setCallConstructorOnLoad(true);
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$Segment", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$ValueIterator
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$ValueIterator");
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentHashMapValueIteratorAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$ValueIterator");
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$ValueIterator", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$EntryIterator
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$EntryIterator");
    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentHashMapEntryIteratorAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$EntryIterator");
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$EntryIterator", bytes, spec.isPreInstrumented());

    if (Vm.isJDK16Compliant()) {
      // java.util.concurrent.ConcurrentHashMap$EntryIterator
      bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$WriteThroughEntry");
      cr = new ClassReader(bytes);
      cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
      cv = new JavaUtilConcurrentHashMapWriteThroughEntryAdapter(cw);
      cr.accept(cv, ClassReader.SKIP_FRAMES);

      bytes = cw.toByteArray();

      spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$WriteThroughEntry");
      spec.setHonorTransient(true);
      spec.markPreInstrumented();
      bytes = doDSOTransform(spec.getClassName(), bytes);
      loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$WriteThroughEntry", bytes, spec.isPreInstrumented());

      // java.util.AbstractMap$SimpleEntry
      bytes = getTerracottaBytes("java.util.AbstractMap$SimpleEntry");
      spec = configHelper.getOrCreateSpec("java.util.AbstractMap$SimpleEntry");
      bytes = doDSOTransform(spec.getClassName(), bytes);
      loadClassIntoJar("java.util.AbstractMap$SimpleEntry", bytes, spec.isPreInstrumented());
    }

    // com.tcclient.util.ConcurrentHashMapEntrySetWrapper$EntryWrapper
    bytes = getTerracottaBytes("com.tcclient.util.ConcurrentHashMapEntrySetWrapper$EntryWrapper");
    spec = configHelper.getOrCreateSpec("com.tcclient.util.ConcurrentHashMapEntrySetWrapper$EntryWrapper");
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("com.tcclient.util.ConcurrentHashMapEntrySetWrapper$EntryWrapper", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$Values
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$Values");
    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$Values");
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$Values", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$KeySet
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$KeySet");
    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$KeySet");
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$KeySet", bytes, spec.isPreInstrumented());

    // java.util.concurrent.ConcurrentHashMap$HashIterator
    bytes = getSystemBytes("java.util.concurrent.ConcurrentHashMap$HashIterator");
    spec = configHelper.getOrCreateSpec("java.util.concurrent.ConcurrentHashMap$HashIterator");
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.ConcurrentHashMap$HashIterator", bytes, spec.isPreInstrumented());
  }

  private final void addInstrumentedJavaUtilConcurrentLinkedBlockingQueue() {
    if (!Vm.isJDK15Compliant()) { return; }

    // Instrumentation for Itr inner class
    byte[] bytes = getSystemBytes("java.util.concurrent.LinkedBlockingQueue$Itr");

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new JavaUtilConcurrentLinkedBlockingQueueIteratorClassAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();
    loadClassIntoJar("java.util.concurrent.LinkedBlockingQueue$Itr", bytes, true);

    // Instrumentation for Node inner class
    bytes = getSystemBytes("java.util.concurrent.LinkedBlockingQueue$Node");

    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentLinkedBlockingQueueNodeClassAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();
    loadClassIntoJar("java.util.concurrent.LinkedBlockingQueue$Node", bytes, true);

    // Instrumentation for LinkedBlockingQueue class
    bytes = getSystemBytes("java.util.concurrent.LinkedBlockingQueue");

    cr = new ClassReader(bytes);
    cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cv = new JavaUtilConcurrentLinkedBlockingQueueClassAdapter(cw);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.util.concurrent.LinkedBlockingQueue",
                                                              "com.tc.object.applicator.LinkedBlockingQueueApplicator");
    // spec.addMethodAdapter(SerializationUtil.QUEUE_PUT_SIGNATURE,
    // new JavaUtilConcurrentLinkedBlockingQueueAdapter.PutAdapter());
    spec.addMethodAdapter(SerializationUtil.TAKE_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.TakeAdapter());
    spec.addMethodAdapter(SerializationUtil.POLL_TIMEOUT_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.TakeAdapter());
    spec.addMethodAdapter(SerializationUtil.POLL_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.TakeAdapter());
    spec.addMethodAdapter(SerializationUtil.CLEAR_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.ClearAdapter());
    spec.addMethodAdapter(SerializationUtil.DRAIN_TO_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.ClearAdapter());
    spec.addMethodAdapter(SerializationUtil.DRAIN_TO_N_SIGNATURE,
                          new JavaUtilConcurrentLinkedBlockingQueueAdapter.RemoveFirstNAdapter());
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);
    loadClassIntoJar("java.util.concurrent.LinkedBlockingQueue", bytes, spec.isPreInstrumented());
  }

  private final void addInstrumentedJavaUtilConcurrentFutureTask() {

    if (!Vm.isJDK15Compliant()) { return; }
    Map instrumentedContext = new HashMap();

    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.util.concurrent.FutureTask");
    spec.setHonorTransient(true);
    spec.setCallConstructorOnLoad(true);
    spec.markPreInstrumented();
    changeClassName("java.util.concurrent.FutureTaskTC", "java.util.concurrent.FutureTaskTC",
                    "java.util.concurrent.FutureTask", instrumentedContext, true);

    configHelper.addWriteAutolock("* java.util.concurrent.FutureTask$Sync.*(..)");

    spec = configHelper.getOrCreateSpec("java.util.concurrent.FutureTask$Sync");
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    spec.addDistributedMethodCall("managedInnerCancel", "()V", true);
    changeClassName("java.util.concurrent.FutureTaskTC$Sync", "java.util.concurrent.FutureTaskTC",
                    "java.util.concurrent.FutureTask", instrumentedContext, true);
  }

  private final void addInstrumentedJavaUtilCollection() {
    TransparencyClassSpec spec = configHelper.getOrCreateSpec("java.util.HashSet",
                                                              "com.tc.object.applicator.HashSetApplicator");
    spec.addIfTrueLogSpec(SerializationUtil.ADD_SIGNATURE);
    spec.addMethodAdapter(SerializationUtil.REMOVE_SIGNATURE, new SetRemoveMethodAdapter("java/util/HashSet",
                                                                                         "java/util/HashMap", "map",
                                                                                         "java/util/HashMap"));
    spec.addAlwaysLogSpec(SerializationUtil.CLEAR_SIGNATURE);
    spec.addSetIteratorWrapperSpec(SerializationUtil.ITERATOR_SIGNATURE);
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.LinkedHashSet", "com.tc.object.applicator.HashSetApplicator");
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.TreeSet", "com.tc.object.applicator.TreeSetApplicator");
    spec.addIfTrueLogSpec(SerializationUtil.ADD_SIGNATURE);
    spec.addMethodAdapter(SerializationUtil.REMOVE_SIGNATURE,
                          new SetRemoveMethodAdapter("java/util/TreeSet", "java/util/TreeMap", "m", Vm
                              .getMajorVersion() >= 6 ? "java/util/NavigableMap" : "java/util/SortedMap"));

    spec.addAlwaysLogSpec(SerializationUtil.CLEAR_SIGNATURE);
    spec.addSetIteratorWrapperSpec(SerializationUtil.ITERATOR_SIGNATURE);
    spec.addViewSetWrapperSpec(SerializationUtil.SUBSET_SIGNATURE);
    spec.addViewSetWrapperSpec(SerializationUtil.HEADSET_SIGNATURE);
    spec.addViewSetWrapperSpec(SerializationUtil.TAILSET_SIGNATURE);
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.LinkedList", "com.tc.object.applicator.ListApplicator");
    spec.addAlwaysLogSpec(SerializationUtil.ADD_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_ALL_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_FIRST_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_LAST_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.SET_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.CLEAR_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_FIRST_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_LAST_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_RANGE_SIGNATURE);
    spec.addMethodAdapter("listIterator(I)Ljava/util/ListIterator;", new LinkedListAdapter.ListIteratorAdapter());
    spec.addMethodAdapter(SerializationUtil.REMOVE_SIGNATURE, new LinkedListAdapter.RemoveAdapter());
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.addSupportMethodCreator(new LinkedListAdapter.RemoveMethodCreator());
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.Vector", "com.tc.object.applicator.ListApplicator");
    spec.addAlwaysLogSpec(SerializationUtil.INSERT_ELEMENT_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_ALL_AT_SIGNATURE);
    // the Vector.addAll(Collection) implementation in the IBM JDK simply delegates
    // to Vector.addAllAt(int, Collection), if addAll is instrumented as well, the
    // vector elements are added twice to the collection
    if (!Vm.isIBM()) {
      spec.addAlwaysLogSpec(SerializationUtil.ADD_ALL_SIGNATURE);
    }
    spec.addAlwaysLogSpec(SerializationUtil.ADD_ELEMENT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_ALL_ELEMENTS_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_ELEMENT_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_RANGE_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.SET_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.SET_ELEMENT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.TRIM_TO_SIZE_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.SET_SIZE_SIGNATURE);
    spec.addMethodAdapter(SerializationUtil.ELEMENTS_SIGNATURE, new VectorAdapter.ElementsAdapter());
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.COPY_INTO_SIGNATURE);
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.Stack", "com.tc.object.applicator.ListApplicator");
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    addSerializationInstrumentedCode(spec);

    spec = configHelper.getOrCreateSpec("java.util.ArrayList", "com.tc.object.applicator.ListApplicator");
    spec.addAlwaysLogSpec(SerializationUtil.ADD_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_ALL_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.ADD_ALL_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_AT_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.REMOVE_RANGE_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.SET_SIGNATURE);
    spec.addAlwaysLogSpec(SerializationUtil.CLEAR_SIGNATURE);
    if (Vm.isJDK15Compliant()) {
      spec.addMethodAdapter(SerializationUtil.REMOVE_SIGNATURE, new ArrayListAdapter.RemoveAdaptor());
      spec.addSupportMethodCreator(new ArrayListAdapter.FastRemoveMethodCreator());
    }
    spec.addArrayCopyMethodCodeSpec(SerializationUtil.TO_ARRAY_SIGNATURE);
    addSerializationInstrumentedCode(spec);
  }

  private final void addSerializationInstrumentedCode(TransparencyClassSpec spec) {
    byte[] bytes = getSystemBytes(spec.getClassName());
    spec.markPreInstrumented();
    bytes = doDSOTransform(spec.getClassName(), bytes);

    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new LogicalClassSerializationAdapter.LogicalClassSerializationClassAdapter(cw, spec
        .getClassName());
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    bytes = cw.toByteArray();
    loadClassIntoJar(spec.getClassName(), bytes, spec.isPreInstrumented());
  }

  private final void addInstrumentedHashtable() {
    String jMapClassNameDots = "java.util.Hashtable";
    String tcMapClassNameDots = "java.util.HashtableTC";
    Map instrumentedContext = new HashMap();
    mergeClass(tcMapClassNameDots, jMapClassNameDots, instrumentedContext, HashtableClassAdapter.getMethods());
  }

  private final void addInstrumentedLinkedHashMap(Map instrumentedContext) {
    String jMapClassNameDots = "java.util.LinkedHashMap";
    String tcMapClassNameDots = "java.util.LinkedHashMapTC";
    mergeClass(tcMapClassNameDots, jMapClassNameDots, instrumentedContext);
  }

  private void addInstrumentedReentrantReadWriteLock() {
    String methodPrefix = "__RWL" + ByteCodeUtil.TC_METHOD_PREFIX;

    String jClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLock";
    String tcClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLockTC";
    Map instrumentedContext = new HashMap();
    mergeReentrantReadWriteLock(tcClassNameDots, jClassNameDots, instrumentedContext, methodPrefix);

    String jInnerClassNameDots;
    String tcInnerClassNameDots;

    jInnerClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLock$ReadLock";
    tcInnerClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLockTC$ReadLock";
    instrumentedContext = new HashMap();
    mergeReadWriteLockInnerClass(tcInnerClassNameDots, jInnerClassNameDots, tcClassNameDots, jClassNameDots,
                                 "ReadLock", "ReadLock", instrumentedContext, methodPrefix);

    jInnerClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock";
    tcInnerClassNameDots = "java.util.concurrent.locks.ReentrantReadWriteLockTC$WriteLock";
    instrumentedContext = new HashMap();
    mergeReadWriteLockInnerClass(tcInnerClassNameDots, jInnerClassNameDots, tcClassNameDots, jClassNameDots,
                                 "WriteLock", "WriteLock", instrumentedContext, methodPrefix);
  }

  private void mergeReadWriteLockInnerClass(String tcInnerClassNameDots, String jInnerClassNameDots,
                                            String tcClassNameDots, String jClassNameDots, String srcInnerClassName,
                                            String targetInnerClassName, Map instrumentedContext, String methodPrefix) {
    String tcInnerClassNameSlashes = tcInnerClassNameDots.replace(ChangeClassNameHierarchyAdapter.DOT_DELIMITER,
                                                                  ChangeClassNameHierarchyAdapter.SLASH_DELIMITER);
    byte[] tcData = getSystemBytes(tcInnerClassNameDots);
    ClassReader tcCR = new ClassReader(tcData);
    ClassNode tcCN = new ClassNode();
    tcCR.accept(tcCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    byte[] jData = getSystemBytes(jInnerClassNameDots);

    // jData = doDSOTransform(jInnerClassNameDots, jData);

    ClassReader jCR = new ClassReader(jData);
    ClassWriter cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);

    ClassInfo jClassInfo = AsmClassInfo.getClassInfo(jInnerClassNameDots, systemLoader);
    TransparencyClassAdapter dsoAdapter = configHelper.createDsoClassAdapterFor(cw, jClassInfo, instrumentationLogger,
                                                                                getClass().getClassLoader(), true,
                                                                                false);
    ClassVisitor cv = new SerialVersionUIDAdder(new MergeTCToJavaClassAdapter(cw, dsoAdapter, jInnerClassNameDots,
                                                                              tcInnerClassNameDots, tcCN,
                                                                              instrumentedContext, methodPrefix, false));
    jCR.accept(cv, ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();

    jData = changeClassNameAndGetBytes(jData, tcInnerClassNameSlashes, tcClassNameDots, jClassNameDots,
                                       srcInnerClassName, targetInnerClassName, instrumentedContext);

    jData = doDSOTransform(jInnerClassNameDots, jData);
    loadClassIntoJar(jInnerClassNameDots, jData, true);
  }

  private void mergeReentrantReadWriteLock(String tcClassNameDots, String jClassNameDots, Map instrumentedContext,
                                           String methodPrefix) {
    byte[] tcData = getSystemBytes(tcClassNameDots);
    ClassReader tcCR = new ClassReader(tcData);
    ClassNode tcCN = new ClassNode();
    tcCR.accept(tcCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    byte[] jData = getSystemBytes(jClassNameDots);

    // jData = doDSOTransform(jClassNameDots, jData);

    ClassReader jCR = new ClassReader(jData);
    ClassWriter cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv1 = new ReentrantReadWriteLockClassAdapter(cw);

    jCR.accept(cv1, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();

    jCR = new ClassReader(jData);
    cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);

    ClassInfo jClassInfo = AsmClassInfo.getClassInfo(jClassNameDots, systemLoader);
    TransparencyClassAdapter dsoAdapter = configHelper
        .createDsoClassAdapterFor(cw, jClassInfo, instrumentationLogger, getClass().getClassLoader(), true, true);
    ClassVisitor cv = new SerialVersionUIDAdder(new MergeTCToJavaClassAdapter(cw, dsoAdapter, jClassNameDots,
                                                                              tcClassNameDots, tcCN,
                                                                              instrumentedContext, methodPrefix, true));
    jCR.accept(cv, ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();
    jData = doDSOTransform(jClassNameDots, jData);
    loadClassIntoJar(jClassNameDots, jData, true);

    String innerClassName = "java/util/concurrent/locks/ReentrantReadWriteLockTC$DsoLock";
    changeClassNameAndGetBytes(innerClassName, tcClassNameDots, jClassNameDots, instrumentedContext);
    changeClassName(innerClassName, tcClassNameDots, jClassNameDots, instrumentedContext, true);
  }

  private void addInstrumentedHashMap() {
    String jMapClassNameDots = "java.util.HashMap";
    String tcMapClassNameDots = "java.util.HashMapTC";
    Map instrumentedContext = new HashMap();
    mergeClass(tcMapClassNameDots, jMapClassNameDots, instrumentedContext);

    addInstrumentedLinkedHashMap(instrumentedContext);
  }

  private final void mergeClass(String tcClassNameDots, String jClassNameDots, Map instrumentedContext) {
    mergeClass(tcClassNameDots, jClassNameDots, instrumentedContext, null);
  }

  private final void mergeClass(String tcClassNameDots, String jClassNameDots, Map instrumentedContext,
                                final MethodNode[] replacedMethods) {
    byte[] tcData = getSystemBytes(tcClassNameDots);

    ClassReader tcCR = new ClassReader(tcData);
    ClassNode tcCN = new ClassNode() {
      public MethodVisitor visitMethod(int maccess, String mname, String mdesc, String msignature, String[] mexceptions) {
        if (replacedMethods != null) {
          for (int i = 0; i < replacedMethods.length; i++) {
            MethodNode replacedMethod = replacedMethods[i];
            if (mname.equals(replacedMethod.name) && mdesc.equals(replacedMethod.desc)) {
              methods.add(replacedMethod);
              return null;
            }
          }
        }
        return super.visitMethod(maccess, mname, mdesc, msignature, mexceptions);
      }
    };
    tcCR.accept(tcCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    byte[] jData = getSystemBytes(jClassNameDots);
    ClassReader jCR = new ClassReader(jData);
    ClassWriter cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv1 = new LinkedHashMapClassAdapter(cw);
    jCR.accept(cv1, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();

    jCR = new ClassReader(jData);
    cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);
    ClassNode jCN = new ClassNode();
    jCR.accept(jCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    ClassInfo jClassInfo = AsmClassInfo.getClassInfo(jClassNameDots, systemLoader);
    TransparencyClassAdapter dsoAdapter = configHelper.createDsoClassAdapterFor(cw, jClassInfo, instrumentationLogger,
                                                                                getClass().getClassLoader(), true,
                                                                                false);
    ClassVisitor cv = new SerialVersionUIDAdder(new MergeTCToJavaClassAdapter(cw, dsoAdapter, jClassNameDots,
                                                                              tcClassNameDots, tcCN,
                                                                              instrumentedContext));
    jCR.accept(cv, ClassReader.SKIP_FRAMES);
    loadClassIntoJar(jClassNameDots, cw.toByteArray(), true);

    List innerClasses = tcCN.innerClasses;
    // load ClassInfo for all inner classes
    for (Iterator i = innerClasses.iterator(); i.hasNext();) {
      InnerClassNode innerClass = (InnerClassNode) i.next();

      if (innerClass.outerName.equals(tcClassNameDots.replace(ChangeClassNameHierarchyAdapter.DOT_DELIMITER,
                                                              ChangeClassNameHierarchyAdapter.SLASH_DELIMITER))) {
        changeClassNameAndGetBytes(innerClass.name, tcClassNameDots, jClassNameDots, instrumentedContext);
      }
    }
    // transform and add inner classes to the boot jar
    for (Iterator i = innerClasses.iterator(); i.hasNext();) {
      InnerClassNode innerClass = (InnerClassNode) i.next();
      if (innerClass.outerName.equals(tcClassNameDots.replace(ChangeClassNameHierarchyAdapter.DOT_DELIMITER,
                                                              ChangeClassNameHierarchyAdapter.SLASH_DELIMITER))) {
        changeClassName(innerClass.name, tcClassNameDots, jClassNameDots, instrumentedContext, false);
      }
    }
  }

  private void changeClassName(String fullClassNameDots, String classNameDotsToBeChanged, String classNameDotsReplaced,
                               Map instrumentedContext, boolean honorTransient) {
    byte[] data = changeClassNameAndGetBytes(fullClassNameDots, classNameDotsToBeChanged, classNameDotsReplaced,
                                             instrumentedContext);

    String replacedClassName = ChangeClassNameRootAdapter.replaceClassName(fullClassNameDots, classNameDotsToBeChanged,
                                                                           classNameDotsReplaced, null, null);
    ClassInfo replacedClassInfo = AsmClassInfo.getClassInfo(replacedClassName, systemLoader);

    ClassReader cr = new ClassReader(data);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor dsoAdapter = configHelper.createDsoClassAdapterFor(cw, replacedClassInfo, instrumentationLogger, //
                                                                    getClass().getClassLoader(), true, honorTransient);
    cr.accept(dsoAdapter, ClassReader.SKIP_FRAMES);

    loadClassIntoJar(replacedClassName, cw.toByteArray(), true);
  }

  private final byte[] changeClassNameAndGetBytes(String fullClassNameDots, String classNameDotsToBeChanged,
                                                  String classNameDotsReplaced, Map instrumentedContext) {
    return changeClassNameAndGetBytes(fullClassNameDots, classNameDotsToBeChanged, classNameDotsReplaced, null, null,
                                      instrumentedContext);
  }

  private final byte[] changeClassNameAndGetBytes(String fullClassNameDots, String classNameDotsToBeChanged,
                                                  String classNameDotsReplaced, String srcInnerClassName,
                                                  String targetInnerClassName, Map instrumentedContext) {
    return changeClassNameAndGetBytes(getSystemBytes(fullClassNameDots), fullClassNameDots, classNameDotsToBeChanged,
                                      classNameDotsReplaced, srcInnerClassName, targetInnerClassName,
                                      instrumentedContext);
  }

  private final byte[] changeClassNameAndGetBytes(byte[] data, String fullClassNameDots,
                                                  String classNameDotsToBeChanged, String classNameDotsReplaced,
                                                  String srcInnerClassName, String targetInnerClassName,
                                                  Map instrumentedContext) {
    ClassReader cr = new ClassReader(data);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new ChangeClassNameRootAdapter(cw, fullClassNameDots, classNameDotsToBeChanged,
                                                     classNameDotsReplaced, srcInnerClassName, targetInnerClassName,
                                                     instrumentedContext, null);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    data = cw.toByteArray();

    AsmClassInfo.getClassInfo(data, systemLoader);

    return data;
  }

  private void addInstrumentedJavaUtilConcurrentLocks() {
    if (!Vm.isJDK15Compliant()) { return; }
    addInstrumentedReentrantReadWriteLock();
    addInstrumentedReentrantLock();
    addInstrumentedConditionObject();
  }

  private void addInstrumentedConditionObject() {
    String classNameDots = "com.tcclient.util.concurrent.locks.ConditionObject";
    byte[] bytes = getSystemBytes(classNameDots);
    TransparencyClassSpec spec = configHelper.getOrCreateSpec(classNameDots);
    spec.disableWaitNotifyCodeSpec("signal()V");
    spec.disableWaitNotifyCodeSpec("signalAll()V");
    spec.setHonorTransient(true);
    spec.markPreInstrumented();
    bytes = doDSOTransform(classNameDots, bytes);
    loadClassIntoJar(classNameDots, bytes, spec.isPreInstrumented());
    configHelper.removeSpec(classNameDots);

    classNameDots = "com.tcclient.util.concurrent.locks.ConditionObject$SyncCondition";
    bytes = getSystemBytes(classNameDots);
    spec = configHelper.getOrCreateSpec(classNameDots);
    spec.markPreInstrumented();
    bytes = doDSOTransform(classNameDots, bytes);
    loadClassIntoJar(classNameDots, bytes, spec.isPreInstrumented());
    configHelper.removeSpec(classNameDots);

  }

  private void addInstrumentedReentrantLock() {
    String jClassNameDots = "java.util.concurrent.locks.ReentrantLock";
    String tcClassNameDots = "java.util.concurrent.locks.ReentrantLockTC";
    Map instrumentedContext = new HashMap();
    mergeReentrantLock(tcClassNameDots, jClassNameDots, instrumentedContext);
  }

  private void mergeReentrantLock(String tcClassNameDots, String jClassNameDots, Map instrumentedContext) {
    String methodPrefix = "__RL" + ByteCodeUtil.TC_METHOD_PREFIX;

    byte[] tcData = getSystemBytes(tcClassNameDots);
    ClassReader tcCR = new ClassReader(tcData);
    ClassNode tcCN = new ClassNode();
    tcCR.accept(tcCN, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    byte[] jData = getSystemBytes(jClassNameDots);
    ClassReader jCR = new ClassReader(jData);
    ClassWriter cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv1 = new ReentrantLockClassAdapter(cw);

    jCR.accept(cv1, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();

    jCR = new ClassReader(jData);
    cw = new ClassWriter(jCR, ClassWriter.COMPUTE_MAXS);

    ClassInfo jClassInfo = AsmClassInfo.getClassInfo(jClassNameDots, systemLoader);
    TransparencyClassAdapter dsoAdapter = configHelper
        .createDsoClassAdapterFor(cw, jClassInfo, instrumentationLogger, getClass().getClassLoader(), true, true);
    ClassVisitor cv = new SerialVersionUIDAdder(new MergeTCToJavaClassAdapter(cw, dsoAdapter, jClassNameDots,
                                                                              tcClassNameDots, tcCN,
                                                                              instrumentedContext, methodPrefix, true));
    jCR.accept(cv, ClassReader.SKIP_FRAMES);
    jData = cw.toByteArray();
    jData = doDSOTransform(jClassNameDots, jData);
    loadClassIntoJar(jClassNameDots, jData, true);
  }

  private final void addInstrumentedWeakHashMap() {
    ClassReader reader = new ClassReader(getSystemBytes("java.util.WeakHashMap"));
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv = new JavaUtilWeakHashMapAdapter().create(writer, null);

    reader.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar("java.util.WeakHashMap", writer.toByteArray(), false);
  }

  private final void addInstrumentedClassLoader() {
    // patch the java.lang.ClassLoader
    ClassLoaderPreProcessorImpl adapter = new ClassLoaderPreProcessorImpl();
    byte[] patched = adapter.preProcess(getSystemBytes("java.lang.ClassLoader"));

    ClassReader cr = new ClassReader(patched);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassVisitor cv = new NamedLoaderAdapter().create(cw, null);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar("java.lang.ClassLoader", cw.toByteArray(), false);
  }

  protected final byte[] doDSOTransform(String name, byte[] data) {
    // adapts the class on the fly
    ClassReader cr = new ClassReader(data);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    ClassInfo classInfo = AsmClassInfo.getClassInfo(data, tcLoader);
    ClassVisitor cv = configHelper.createClassAdapterFor(cw, classInfo, instrumentationLogger, getClass()
        .getClassLoader(), true);
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    return cw.toByteArray();
  }

  private final void adaptClassIfNotAlreadyIncluded(String className, Class adapter) {
    if (bootJar.classLoaded(className)) { return; }

    byte[] orig = getSystemBytes(className);

    ClassReader cr = new ClassReader(orig);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    ClassVisitor cv;
    try {
      cv = (ClassVisitor) adapter.getConstructor(new Class[] { ClassVisitor.class }).newInstance(new Object[] { cw });
    } catch (Exception e) {
      throw exit("Can't instaniate class apapter using class " + adapter, e);
    }
    cr.accept(cv, ClassReader.SKIP_FRAMES);

    loadClassIntoJar(className, cw.toByteArray(), false);
  }

  protected void announce(String msg) {
    // if (!quiet) System.out.println(msg);
    if (!quiet) consoleLogger.info(msg);
  }

  private final static File getInstallationDir() {
    try {
      return Directories.getInstallationRoot();
    } catch (FileNotFoundException fnfe) {
      return null;
    }
  }

  private static final String MAKE_MODE = "make";
  private static final String SCAN_MODE = "scan";

  public final static void main(String[] args) throws Exception {
    File installDir = getInstallationDir();
    String outputFileOptionMsg = "path to boot JAR file"
                                 + (installDir != null ? "\ndefault: [TC_INSTALL_DIR]/lib/dso-boot" : "");
    Option targetFileOption = new Option(TARGET_FILE_OPTION, true, outputFileOptionMsg);
    targetFileOption.setArgName("file");
    targetFileOption.setLongOpt("bootjar-file");
    targetFileOption.setArgs(1);
    targetFileOption.setRequired(installDir == null);
    targetFileOption.setType(String.class);

    Option configFileOption = new Option("f", "config", true, "configuration file (optional)");
    configFileOption.setArgName("file-or-URL");
    configFileOption.setType(String.class);
    configFileOption.setRequired(false);

    Option overwriteOption = new Option("w", "overwrite", false, "always make the boot JAR file");
    overwriteOption.setType(String.class);
    overwriteOption.setRequired(false);

    Option verboseOption = new Option("v", "verbose");
    verboseOption.setType(String.class);
    verboseOption.setRequired(false);

    Option helpOption = new Option("h", "help");
    helpOption.setType(String.class);
    helpOption.setRequired(false);

    Options options = new Options();
    options.addOption(targetFileOption);
    options.addOption(configFileOption);
    options.addOption(overwriteOption);
    options.addOption(verboseOption);
    options.addOption(helpOption);

    String mode = MAKE_MODE;
    CommandLine commandLine = null;
    try {
      commandLine = new PosixParser().parse(options, args);
      if (commandLine.getArgList().size() > 0) {
        mode = commandLine.getArgList().get(0).toString().toLowerCase();
      }
    } catch (ParseException pe) {
      new HelpFormatter().printHelp("java " + BootJarTool.class.getName(), options);
      System.exit(1);
    }

    final String MAKE_OR_SCAN_MODE = "<" + MAKE_MODE + "|" + SCAN_MODE + ">";
    if (!mode.equals(MAKE_MODE) && !mode.equals(SCAN_MODE)) {
      new HelpFormatter().printHelp("java " + BootJarTool.class.getName() + " " + MAKE_OR_SCAN_MODE, options);
      System.exit(1);
    }

    if (commandLine.hasOption("h")) {
      new HelpFormatter().printHelp("java " + BootJarTool.class.getName() + " " + MAKE_OR_SCAN_MODE, options);
      System.exit(1);
    }

    if (!commandLine.hasOption("f")
        && System.getProperty(TVSConfigurationSetupManagerFactory.CONFIG_FILE_PROPERTY_NAME) == null) {
      String cwd = System.getProperty("user.dir");
      File localConfig = new File(cwd, DEFAULT_CONFIG_SPEC);
      String configSpec;

      if (localConfig.exists()) {
        configSpec = localConfig.getAbsolutePath();
      } else {
        configSpec = StandardTVSConfigurationSetupManagerFactory.DEFAULT_CONFIG_URI;
      }

      String[] newArgs = new String[args.length + 2];
      System.arraycopy(args, 0, newArgs, 0, args.length);
      newArgs[newArgs.length - 2] = "-f";
      newArgs[newArgs.length - 1] = configSpec;

      commandLine = new PosixParser().parse(options, newArgs);
    }

    StandardTVSConfigurationSetupManagerFactory factory;
    factory = new StandardTVSConfigurationSetupManagerFactory(commandLine, false,
                                                              new FatalIllegalConfigurationChangeHandler());
    boolean verbose = commandLine.hasOption("v");
    TCLogger logger = verbose ? CustomerLogging.getConsoleLogger() : new NullTCLogger();
    L1TVSConfigurationSetupManager config = factory.createL1TVSConfigurationSetupManager(logger);

    File targetFile;

    if (!commandLine.hasOption(TARGET_FILE_OPTION)) {
      File libDir = new File(installDir, "lib");
      targetFile = new File(libDir, "dso-boot");
      if (!targetFile.exists()) {
        targetFile.mkdirs();
      }
    } else {
      targetFile = new File(commandLine.getOptionValue(TARGET_FILE_OPTION)).getAbsoluteFile();
    }

    if (targetFile.isDirectory()) {
      targetFile = new File(targetFile, BootJarSignature.getBootJarNameForThisVM());
    }

    // This used to be a provider that read from a specified rt.jar (to let us create boot jars for other platforms).
    // That requirement is no more, but might come back, so I'm leaving at least this much scaffolding in place
    // WAS: systemProvider = new RuntimeJarBytesProvider(...)
    ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
    BootJarTool bjTool = new BootJarTool(new StandardDSOClientConfigHelperImpl(config, false), targetFile,
                                         systemLoader, !verbose);
    if (mode.equals(MAKE_MODE)) {
      boolean makeItAnyway = commandLine.hasOption("w");
      if (makeItAnyway || !targetFile.exists() || (targetFile.exists() && !bjTool.isBootJarComplete(targetFile))) {
        // Don't reuse boot jar tool instance since its config might have been mutated by isBootJarComplete()
        bjTool = new BootJarTool(new StandardDSOClientConfigHelperImpl(config, false), targetFile, systemLoader,
                                 !verbose);
        bjTool.generateJar();
      }
      bjTool.verifyJar(targetFile);
    } else if (mode.equals(SCAN_MODE)) {
      bjTool.scanJar(targetFile);
    } else {
      consoleLogger.fatal("\nInvalid mode specified, valid modes are: '" + MAKE_MODE + "' and '" + SCAN_MODE + "';"
                          + "use the -h option to view the options for this tool.");
      System.exit(1);
    }
  }
}
