/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.bytecode.hook.impl;

import com.tc.asm.ClassReader;
import com.tc.asm.ClassVisitor;
import com.tc.asm.ClassWriter;
import com.tc.asm.commons.EmptyVisitor;
import com.tc.aspectwerkz.definition.SystemDefinition;
import com.tc.aspectwerkz.definition.deployer.StandardAspectModuleDeployer;
import com.tc.aspectwerkz.exception.WrappedRuntimeException;
import com.tc.aspectwerkz.expression.ExpressionContext;
import com.tc.aspectwerkz.expression.PointcutType;
import com.tc.aspectwerkz.reflect.ClassInfo;
import com.tc.aspectwerkz.reflect.ClassInfoHelper;
import com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo;
import com.tc.aspectwerkz.transform.InstrumentationContext;
import com.tc.aspectwerkz.transform.WeavingStrategy;
import com.tc.aspectwerkz.transform.inlining.weaver.AddInterfaceVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.AddMixinMethodsVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.AddWrapperVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.AlreadyAddedMethodAdapter;
import com.tc.aspectwerkz.transform.inlining.weaver.ConstructorBodyVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.ConstructorCallVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.FieldSetFieldGetVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.HandlerVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.InstanceLevelAspectVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.JoinPointInitVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.LabelToLineNumberVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.MethodCallVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.MethodExecutionVisitor;
import com.tc.aspectwerkz.transform.inlining.weaver.StaticInitializationVisitor;
import com.tc.exception.TCLogicalSubclassNotPortableException;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.object.bytecode.ByteCodeUtil;
import com.tc.object.bytecode.ClassAdapterFactory;
import com.tc.object.bytecode.RenameClassesAdapter;
import com.tc.object.bytecode.SafeSerialVersionUIDAdder;
import com.tc.object.config.ClassReplacementMapping;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.logging.InstrumentationLogger;
import com.tc.object.logging.InstrumentationLoggerImpl;
import com.tc.util.AdaptedClassDumper;
import com.tc.util.InitialClassDumper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A weaving strategy implementing a weaving scheme based on statical compilation, and no reflection.
 *
 * @author <a href="mailto:jboner@codehaus.org">Jonas Bon&#233;r </a>
 * @author <a href="mailto:alex@gnilux.com">Alexandre Vasseur </a>
 */
public class DefaultWeavingStrategy implements WeavingStrategy {

  static {
    // Load the InitialClassDumper class in the same class loader as the
    // current class to ensure that it will not be transformed while
    // being loaded by a child class loader as this would result in a
    // ClassCircularityError exception
    InitialClassDumper dummy = InitialClassDumper.INSTANCE;
    if (false && dummy != dummy) {
      // silence eclipse warning
    }
  }

  private static final TCLogger       consoleLogger = CustomerLogging.getConsoleLogger();

  private final DSOClientConfigHelper m_configHelper;
  private final InstrumentationLogger m_logger;
  private final InstrumentationLogger m_instrumentationLogger;

  public DefaultWeavingStrategy(final DSOClientConfigHelper configHelper, InstrumentationLogger instrumentationLogger) {
    m_configHelper = configHelper;
    m_instrumentationLogger = instrumentationLogger;
    m_logger = new InstrumentationLoggerImpl(m_configHelper.getInstrumentationLoggingOptions());

    // deploy all system aspect modules
    StandardAspectModuleDeployer.deploy(getClass().getClassLoader(), StandardAspectModuleDeployer.ASPECT_MODULES);

    // workaround for ClassCircularityError in ConcurrentHashMap
    configHelper.getAspectModules().entrySet().iterator();
  }

  /**
   * Performs the weaving of the target class.
   *
   * @param className
   * @param context
   */
  public void transform(String className, final InstrumentationContext context) {
    try {
      final byte[] bytecode = context.getInitialBytecode();
      InitialClassDumper.INSTANCE.write(className, bytecode);

      final ClassLoader loader = context.getLoader();

      Map aspectModules = m_configHelper.getAspectModules();
      for (Iterator it = aspectModules.entrySet().iterator(); it.hasNext();) {
        Map.Entry e = (Map.Entry) it.next();
        if (className.startsWith((String) e.getKey())) {
          List modules = (List) e.getValue();
          for (Iterator it2 = modules.iterator(); it2.hasNext();) {
            StandardAspectModuleDeployer.deploy(loader, (String) it2.next());
          }
        }
      }

      ClassInfo classInfo = AsmClassInfo.getClassInfo(className, bytecode, loader);

      // skip Java reflect proxies for which we cannot get the resource as a stream
      // which leads to warnings when using annotation matching
      // Note: we use an heuristic assuming JDK proxy are classes named "$..."
      // to avoid to call getSuperClass everytime
      if (classInfo.getName().startsWith("$") && classInfo.getSuperclass().getName().equals("java.lang.reflect.Proxy")) {
        context.setCurrentBytecode(context.getInitialBytecode());
        return;
      }

      // filtering out all proxy classes that have been transformed by DSO already
      if (context.isProxy() && isInstrumentedByDSO(classInfo)) {
        context.setCurrentBytecode(context.getInitialBytecode());
        return;
      }

      final boolean isDsoAdaptable = m_configHelper.shouldBeAdapted(classInfo);
      final boolean hasCustomAdapter = m_configHelper.hasCustomAdapter(classInfo);

      // TODO match on (within, null, classInfo) should be equivalent to those ones.
      final Set definitions = context.getDefinitions();
      final ExpressionContext[] ctxs = new ExpressionContext[] {
          new ExpressionContext(PointcutType.EXECUTION, classInfo, classInfo),
          new ExpressionContext(PointcutType.CALL, null, classInfo),
          new ExpressionContext(PointcutType.GET, null, classInfo),
          new ExpressionContext(PointcutType.SET, null, classInfo),
          new ExpressionContext(PointcutType.HANDLER, null, classInfo),
          new ExpressionContext(PointcutType.STATIC_INITIALIZATION, classInfo, classInfo),
          new ExpressionContext(PointcutType.WITHIN, classInfo, classInfo) };

      // has AW aspects?
      final boolean isAdvisable = !classFilter(definitions, ctxs, classInfo);

      if (!isAdvisable && !isDsoAdaptable && !hasCustomAdapter) {
        context.setCurrentBytecode(context.getInitialBytecode());
        return;
      }

      if (m_instrumentationLogger.getClassInclusion()) {
        m_instrumentationLogger.classIncluded(className);
      }

      // handle replacement classes
      if (isDsoAdaptable || hasCustomAdapter) {
        ClassReplacementMapping mapping = m_configHelper.getClassReplacementMapping();
        String replacementClassName = mapping.getReplacementClassName(className);

        // check if there's a replacement class
        if (replacementClassName != null && !replacementClassName.equals(className)) {
          // obtain the resource of the replacement class either from a module bundle, or from the
          // active classloader
          URL replacementResource = mapping.getReplacementResource(replacementClassName, loader);
          if (replacementResource == null) { throw new ClassNotFoundException("No resource found for class: "
                                                                              + replacementClassName); }

          // obtain the bytes of the replacement class
          InputStream is = replacementResource.openStream();
          try {
            byte[] replacementBytes = ByteCodeUtil.getBytesForInputstream(is);

            // perform the rename transformation so that it can be used instead of the original class
            ClassReader cr = new ClassReader(replacementBytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new RenameClassesAdapter(cw, mapping);
            cr.accept(cv, ClassReader.SKIP_FRAMES);

            context.setCurrentBytecode(cw.toByteArray());

            // update the classInfo
            classInfo = AsmClassInfo.newClassInfo(context.getCurrentBytecode(), loader);

          } catch (IOException e) {
            throw new ClassNotFoundException("Error reading bytes for " + replacementResource, e);
          } finally {
            is.close();
          }
        }
      }

      // ------------------------------------------------
      // -- Phase AW -- weave in aspects
      if (isAdvisable) {
        // compute CALL + GET/SET early matching results to avoid registering useless visitors
        final boolean filterForCall = classFilterFor(definitions, new ExpressionContext[] {
            new ExpressionContext(PointcutType.CALL, null, classInfo),
            new ExpressionContext(PointcutType.WITHIN, classInfo, classInfo) });// FIXME - within make match
        // all
        final boolean filterForGetSet = classFilterFor(definitions, new ExpressionContext[] {
            new ExpressionContext(PointcutType.GET, null, classInfo),
            new ExpressionContext(PointcutType.SET, null, classInfo),
            new ExpressionContext(PointcutType.WITHIN, classInfo, classInfo) });// FIXME - within make match
        // all
        final boolean filterForHandler = classFilterFor(definitions, new ExpressionContext[] {
            new ExpressionContext(PointcutType.HANDLER, null, classInfo),
            new ExpressionContext(PointcutType.WITHIN, classInfo, classInfo) });// FIXME - within make match
        // all

        // note: for staticinitialization we do an exact match right there
        boolean filterForStaticinitialization = !classInfo.hasStaticInitializer()
                                                || classFilterFor(
                                                                  definitions,
                                                                  new ExpressionContext[] { new ExpressionContext(
                                                                                                                  PointcutType.STATIC_INITIALIZATION,
                                                                                                                  classInfo
                                                                                                                      .staticInitializer(),
                                                                                                                  classInfo) });
        if (!filterForStaticinitialization) {
          filterForStaticinitialization = !hasPointcut(definitions,
                                                       new ExpressionContext(PointcutType.STATIC_INITIALIZATION,
                                                                             classInfo.staticInitializer(), classInfo));
        }

        // prepare ctor call jp
        final ClassReader crLookahead = new ClassReader(context.getCurrentBytecode());
        HashMap newInvocationsByCallerMemberHash = null;
        if (!filterForCall) {
          newInvocationsByCallerMemberHash = new HashMap();
          crLookahead
              .accept(
                      new ConstructorCallVisitor.LookaheadNewDupInvokeSpecialInstructionClassAdapter(
                                                                                                     newInvocationsByCallerMemberHash),
                      ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        // prepare handler jp, by gathering ALL catch blocks and their exception type
        List catchLabels = new ArrayList();
        if (!filterForHandler) {
          final ClassVisitor cv = new EmptyVisitor();
          HandlerVisitor.LookaheadCatchLabelsClassAdapter lookForCatches = //
          new HandlerVisitor.LookaheadCatchLabelsClassAdapter(cv, loader, classInfo, context, catchLabels);
          // we must visit exactly as we will do further on with debug info (that produces extra labels)
          final ClassReader crLookahead2 = new ClassReader(context.getCurrentBytecode());
          crLookahead2.accept(lookForCatches, ClassReader.SKIP_FRAMES);
        }

        // gather wrapper methods to support multi-weaving
        // skip annotations visit and debug info by using the lookahead read-only classreader
        Set addedMethods = new HashSet();
        crLookahead.accept(new AlreadyAddedMethodAdapter(addedMethods), ClassReader.SKIP_DEBUG
                                                                        | ClassReader.SKIP_FRAMES);

        // ------------------------------------------------
        // -- Phase 1 -- type change (ITDs)
        final ClassReader readerPhase1 = new ClassReader(context.getCurrentBytecode());
        final ClassWriter writerPhase1 = new ClassWriter(readerPhase1, ClassWriter.COMPUTE_MAXS);
        ClassVisitor reversedChainPhase1 = new AddMixinMethodsVisitor(writerPhase1, classInfo, context, addedMethods);
        reversedChainPhase1 = new AddInterfaceVisitor(reversedChainPhase1, classInfo, context);
        readerPhase1.accept(reversedChainPhase1, ClassReader.SKIP_FRAMES);
        context.setCurrentBytecode(writerPhase1.toByteArray());

        // ------------------------------------------------
        // update the class info with new ITDs
        classInfo = AsmClassInfo.newClassInfo(context.getCurrentBytecode(), loader);

        // ------------------------------------------------
        // -- Phase 2 -- advice
        final ClassReader readerPhase2 = new ClassReader(context.getCurrentBytecode());
        final ClassWriter writerPhase2 = new ClassWriter(readerPhase2, ClassWriter.COMPUTE_MAXS);
        ClassVisitor reversedChainPhase2 = new InstanceLevelAspectVisitor(writerPhase2, classInfo, context);
        reversedChainPhase2 = new MethodExecutionVisitor(reversedChainPhase2, classInfo, context, addedMethods);
        reversedChainPhase2 = new ConstructorBodyVisitor(reversedChainPhase2, classInfo, context, addedMethods);
        if (!filterForStaticinitialization) {
          reversedChainPhase2 = new StaticInitializationVisitor(reversedChainPhase2, context, addedMethods);
        }
        reversedChainPhase2 = new HandlerVisitor(reversedChainPhase2, context, catchLabels);
        if (!filterForCall) {
          reversedChainPhase2 = new MethodCallVisitor(reversedChainPhase2, loader, classInfo, context);
          reversedChainPhase2 = new ConstructorCallVisitor(reversedChainPhase2, loader, classInfo, context,
                                                           newInvocationsByCallerMemberHash);
        }
        if (!filterForGetSet) {
          reversedChainPhase2 = new FieldSetFieldGetVisitor(reversedChainPhase2, loader, classInfo, context);
        }
        reversedChainPhase2 = new LabelToLineNumberVisitor(reversedChainPhase2, context);

        readerPhase2.accept(reversedChainPhase2, ClassReader.SKIP_FRAMES);
        context.setCurrentBytecode(writerPhase2.toByteArray());

        // ------------------------------------------------
        // -- AW Finalization -- JP init code and wrapper methods
        if (context.isAdvised()) {
          ClassReader readerPhase3 = new ClassReader(context.getCurrentBytecode());
          final ClassWriter writerPhase3 = new ClassWriter(readerPhase3, ClassWriter.COMPUTE_MAXS);
          ClassVisitor reversedChainPhase3 = new AddWrapperVisitor(writerPhase3, context, addedMethods);
          reversedChainPhase3 = new JoinPointInitVisitor(reversedChainPhase3, context);
          readerPhase3.accept(reversedChainPhase3, ClassReader.SKIP_FRAMES);
          context.setCurrentBytecode(writerPhase3.toByteArray());
        }
      }

      // ------------------------------------------------
      // -- Phase DSO -- DSO clustering
      if (hasCustomAdapter) {
        ClassAdapterFactory factory = m_configHelper.getCustomAdapter(classInfo);
        final ClassReader reader = new ClassReader(context.getCurrentBytecode());
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor adapter = factory.create(writer, context.getLoader());
        reader.accept(adapter, ClassReader.SKIP_FRAMES);
        context.setCurrentBytecode(writer.toByteArray());
      }

      if (isDsoAdaptable) {
        final ClassReader dsoReader = new ClassReader(context.getCurrentBytecode());
        final ClassWriter dsoWriter = new ClassWriter(dsoReader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor dsoVisitor = m_configHelper.createClassAdapterFor(dsoWriter, classInfo, m_logger, loader);
        try {
          dsoReader.accept(dsoVisitor, ClassReader.SKIP_FRAMES);
          context.setCurrentBytecode(dsoWriter.toByteArray());
        } catch (TCLogicalSubclassNotPortableException e) {
          List l = new ArrayList(1);
          l.add(e.getSuperClassName());
          m_logger.subclassOfLogicallyManagedClasses(e.getClassName(), l);
        }

        // CDV-237
        final String[] missingRoots = m_configHelper.getMissingRootDeclarations(classInfo);
        for (int i = 0; i < missingRoots.length; i++) {
          String MESSAGE = "The root expression ''{0}'' meant for the class ''{1}'' "
                           + "has no effect, make sure that it is a valid expression "
                           + "and that it is spelled correctly.";
          Object[] info = { missingRoots[i], classInfo.getName() };
          String message = MessageFormat.format(MESSAGE, info);
          consoleLogger.warn(message);
        }
      }

      // ------------------------------------------------
      // -- Generic finalization -- serialVersionUID
      if (context.isAdvised() || isDsoAdaptable) {
        if (ClassInfoHelper.implementsInterface(classInfo, "java.io.Serializable")) {
          ClassReader readerPhase3 = new ClassReader(context.getCurrentBytecode());
          final ClassWriter writerPhase3 = new ClassWriter(readerPhase3, ClassWriter.COMPUTE_MAXS);
          readerPhase3.accept(new SafeSerialVersionUIDAdder(writerPhase3), ClassReader.SKIP_FRAMES);
          context.setCurrentBytecode(writerPhase3.toByteArray());
        }
      }

      AdaptedClassDumper.INSTANCE.write(className, context.getCurrentBytecode());
    } catch (Throwable t) {
      t.printStackTrace();
      throw new WrappedRuntimeException(t);
    }
  }

  private boolean isInstrumentedByDSO(ClassInfo classInfo) {
    ClassInfo[] interfaces = classInfo.getInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      if (interfaces[i].getName().equals("com.tc.object.bytecode.TransparentAccess")) { return true; }
    }
    return false;
  }

  /**
   * Filters out the classes that are not eligible for transformation.
   *
   * @param definitions the definitions
   * @param ctxs an array with the contexts
   * @param classInfo the class to filter
   * @return boolean true if the class should be filtered out
   */
  private static boolean classFilter(final Set definitions, final ExpressionContext[] ctxs, final ClassInfo classInfo) {
    if (classInfo.isInterface()) { return true; }
    for (Iterator defs = definitions.iterator(); defs.hasNext();) {
      if (!classFilter((SystemDefinition) defs.next(), ctxs, classInfo)) { return false; }
    }
    return true;
  }

  /**
   * Filters out the classes that are not eligible for transformation.
   *
   * @param definition the definition
   * @param ctxs an array with the contexts
   * @param classInfo the class to filter
   * @return boolean true if the class should be filtered out
   * @TODO: when a class had execution pointcut that were removed it must be unweaved, thus not filtered out How to
   *        handle that? cache lookup? or custom class level attribute ?
   */
  private static boolean classFilter(final SystemDefinition definition, final ExpressionContext[] ctxs,
                                     final ClassInfo classInfo) {
    if (classInfo.isInterface()) { return true; }
    String className = classInfo.getName();
    if (definition.inExcludePackage(className)) { return true; }
    if (!definition.inIncludePackage(className)) { return true; }
    if (definition.isAdvised(ctxs)) { return false; }
    if (definition.hasMixin(ctxs)) { return false; }
    if (definition.hasIntroducedInterface(ctxs)) { return false; }
    return !definition.inPreparePackage(className);
  }

  private static boolean classFilterFor(final Set definitions, final ExpressionContext[] ctxs) {
    for (Iterator defs = definitions.iterator(); defs.hasNext();) {
      if (!classFilterFor((SystemDefinition) defs.next(), ctxs)) { return false; }
    }
    return true;
  }

  private static boolean classFilterFor(final SystemDefinition definition, final ExpressionContext[] ctxs) {
    return !definition.isAdvised(ctxs);
  }

  private static boolean hasPointcut(final Set definitions, final ExpressionContext ctx) {
    for (Iterator defs = definitions.iterator(); defs.hasNext();) {
      if (hasPointcut((SystemDefinition) defs.next(), ctx)) { return true; }
    }
    return false;
  }

  private static boolean hasPointcut(final SystemDefinition definition, final ExpressionContext ctx) {
    return definition.hasPointcut(ctx);
  }
}
