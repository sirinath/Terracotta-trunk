/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package org.terracotta.modules.hibernate_3_2_5.object.applicator;

import com.tc.exception.TCNotSupportedMethodException;
import com.tc.exception.TCRuntimeException;
import com.tc.object.ClientObjectManager;
import com.tc.object.ObjectID;
import com.tc.object.TCObject;
import com.tc.object.TraversedReferences;
import com.tc.object.applicator.BaseApplicator;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.api.PhysicalAction;
import com.tc.object.tx.optimistic.OptimisticTransactionManager;
import com.tc.util.Assert;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * @author Antonio Si
 */
public class HibernateProxyApplicator extends BaseApplicator {
  private static final String PERSISTENT_CLASS_FIELD_NAME          = "org.hibernate.proxy.HibernateProxy.persistentClass";
  private static final String ENTITY_NAME_FIELD_NAME               = "org.hibernate.proxy.HibernateProxy.entityName";
  private static final String INTERFACES_FIELD_NAME                = "org.hibernate.proxy.HibernateProxy.interfaces";
  private static final String ID_FIELD_NAME                        = "org.hibernate.proxy.HibernateProxy.id";
  private static final String TARGET_FIELD_NAME                    = "org.hibernate.proxy.HibernateProxy.target";
  private static final String GET_IDENTIFIER_METHOD_FIELD_NAME     = "org.hibernate.proxy.HibernateProxy.getIdentifierMethod";
  private static final String SET_IDENTIFIER_METHOD_FIELD_NAME     = "org.hibernate.proxy.HibernateProxy.setIdentifierMethod";
  private static final String COMPONENT_ID_TYPE_FIELD_NAME         = "org.hibernate.proxy.HibernateProxy.componentIdType";

  private static final String BASIC_LAZY_INITIALIZER_CLASS_NAME    = "org.hibernate.proxy.pojo.BasicLazyInitializer";
  //private static final String BASIC_LAZY_INITIALIZER_CLASS_NAME    = "org.hibernate.proxy.BasicLazyInitializer";
  private static final String ABSTRACT_LAZY_INITIALIZER_CLASS_NAME = "org.hibernate.proxy.AbstractLazyInitializer";
  private static final String CGLIB_LAZY_INITIALIZER_CLASS_NAME    = "org.hibernate.proxy.pojo.cglib.CGLIBLazyInitializer";
  //private static final String CGLIB_LAZY_INITIALIZER_CLASS_NAME    = "org.hibernate.proxy.CGLIBLazyInitializer";
  private static final String HIBERNATE_PROXY_CLASS_NAME           = "org.hibernate.proxy.HibernateProxy";
  private static final String ABSTRACT_COMPONENT_CLASS_NAME        = "org.hibernate.type.AbstractComponentType";
  private static final String SESSION_IMPLEMENTOR_CLASS_NAME       = "org.hibernate.engine.SessionImplementor";

  public HibernateProxyApplicator(DNAEncoding encoding) {
    super(encoding);
  }

  public TraversedReferences getPortableObjects(Object pojo, TraversedReferences addTo) {
    Object lazyInitializer = getLazyInitializer(pojo);
    Object persistenceClass = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "persistentClass");
    Object entityName = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "entityName");
    Object interfaces = getField(CGLIB_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "interfaces");
    Object id = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "id");
    Object target = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "target");
    Object getIdentifierMethod = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "getIdentifierMethod");
    Object setIdentifierMethod = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "setIdentifierMethod");
    Object componentIdType = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "componentIdType");

    addTo.addAnonymousReference(persistenceClass);
    addTo.addAnonymousReference(entityName);
    addTo.addAnonymousReference(interfaces);
    addTo.addAnonymousReference(id);
    addTo.addAnonymousReference(target);
    // addTo.addAnonymousReference(session);
    addTo.addAnonymousReference(getIdentifierMethod);
    addTo.addAnonymousReference(setIdentifierMethod);
    addTo.addAnonymousReference(componentIdType);
    return addTo;
  }

  private Object getLazyInitializer(Object pojo) {
    try {
      Class hibernateProxyClass = pojo.getClass().getClassLoader().loadClass(HIBERNATE_PROXY_CLASS_NAME);
      Method m = hibernateProxyClass.getDeclaredMethod("getHibernateLazyInitializer", new Class[0]);
      m.setAccessible(true);
      return m.invoke(pojo, new Object[0]);
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    } catch (SecurityException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new TCRuntimeException(e);
    }
  }

  private Object getField(String className, Object pojo, String fieldName) {
    try {
      Class loadedClazz = pojo.getClass().getClassLoader().loadClass(className);
      Field field = loadedClazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(pojo);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchFieldException e) {
      throw new TCRuntimeException(e);
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    }
  }

  private void invokeMethod(String className, Object pojo, String methodName, Object value) {
    try {
      Class loadedClazz = pojo.getClass().getClassLoader().loadClass(className);
      Method m = loadedClazz.getDeclaredMethod(methodName, new Class[] { Object.class });
      m.setAccessible(true);
      m.invoke(pojo, new Object[] { value });
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    } catch (SecurityException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new TCRuntimeException(e);
    }
  }

  public void hydrate(ClientObjectManager objectManager, TCObject tcObject, DNA dna, Object po) throws IOException,
      IllegalArgumentException, ClassNotFoundException {
    // 
  }

  public void dehydrate(ClientObjectManager objectManager, TCObject tcObject, DNAWriter writer, Object pojo) {
    Object lazyInitializer = getLazyInitializer(pojo);

    Object persistenceClass = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "persistentClass");
    Object entityName = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "entityName");
    Object interfaces = getField(CGLIB_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "interfaces");
    Object id = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "id");
    Object target = getField(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "target");
    target = getDehydratableObject(target, objectManager);
    // Object session = getField(AbstractLazyInitializer.class.getName(), lazyInitializer, "session");
    // session = getDehydratableObject(session, objectManager);
    Object getIdentifierMethod = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "getIdentifierMethod");
    getIdentifierMethod = getDehydratableObject(getIdentifierMethod, objectManager);
    Object setIdentifierMethod = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "setIdentifierMethod");
    setIdentifierMethod = getDehydratableObject(setIdentifierMethod, objectManager);
    Object componentIdType = getField(BASIC_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "componentIdType");
    componentIdType = getDehydratableObject(componentIdType, objectManager);

    writer.addPhysicalAction(PERSISTENT_CLASS_FIELD_NAME, persistenceClass);
    writer.addPhysicalAction(ENTITY_NAME_FIELD_NAME, entityName);
    writer.addPhysicalAction(INTERFACES_FIELD_NAME, interfaces);
    writer.addPhysicalAction(ID_FIELD_NAME, id);
    writer.addPhysicalAction(TARGET_FIELD_NAME, target);
    // writer.addPhysicalAction(SESSION_FIELD_NAME, session);
    writer.addPhysicalAction(GET_IDENTIFIER_METHOD_FIELD_NAME, getIdentifierMethod);
    writer.addPhysicalAction(SET_IDENTIFIER_METHOD_FIELD_NAME, setIdentifierMethod);
    writer.addPhysicalAction(COMPONENT_ID_TYPE_FIELD_NAME, componentIdType);
  }

  public Object getNewInstance(ClientObjectManager objectManager, DNA dna) throws IOException, ClassNotFoundException {
    DNACursor cursor = dna.getCursor();
    Assert.assertEquals(8, cursor.getActionCount());

    cursor.next(encoding);
    PhysicalAction a = cursor.getPhysicalAction();
    Class persistenceClass = (Class) a.getObject();

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    String entityName = (String) a.getObject();

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    Object[] interfacesObj = (Object[]) a.getObject();
    Class[] interfaces = new Class[interfacesObj.length];
    System.arraycopy(interfacesObj, 0, interfaces, 0, interfacesObj.length);

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    Serializable id = (Serializable) a.getObject();

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    ObjectID targetId = (ObjectID) a.getObject();
    Object target = objectManager.lookupObject(targetId);

    // cursor.next(encoding);
    // a = cursor.getPhysicalAction();
    // ObjectID sessionId = (ObjectID)a.getObject();
    // Object session = objectManager.lookupObject(sessionId);
    Object session = getSession(persistenceClass.getClassLoader());

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    ObjectID getIdentifierId = (ObjectID) a.getObject();
    Method getIdentifierMethod = (Method) objectManager.lookupObject(getIdentifierId);

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    ObjectID setIdentifierId = (ObjectID) a.getObject();
    Method setIdentifierMethod = (Method) objectManager.lookupObject(setIdentifierId);

    cursor.next(encoding);
    a = cursor.getPhysicalAction();
    ObjectID componentIdTypeId = (ObjectID) a.getObject();
    Object componentIdType = objectManager.lookupObject(componentIdTypeId);

    Object hibernateProxy = createHibernateProxy(persistenceClass, entityName, interfaces, id, session,
                                                 getIdentifierMethod, setIdentifierMethod, componentIdType);
    
    if (target != null) {
      Object lazyInitializer = getLazyInitializer(hibernateProxy);
      invokeMethod(ABSTRACT_LAZY_INITIALIZER_CLASS_NAME, lazyInitializer, "setImplementation", target);
    }
    
    closeSession(session);
    
    return hibernateProxy;
  }
  
  private void closeSession(Object session) {
    try {
      Method m = session.getClass().getDeclaredMethod("close", new Class[0]);
      m.invoke(session, new Object[0]);
    } catch (SecurityException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new TCRuntimeException(e);
    }
  }

  private Object getSession(ClassLoader loader) {
    try {
      Class hibernateUtilClass = Class.forName("org.terracotta.modules.hibernate_3_2_5.util.HibernateUtil", true, loader);
      Method m = hibernateUtilClass.getDeclaredMethod("getSessionFactory", new Class[0]);
      Object sessionFactory = m.invoke(null, new Object[0]);

      Class sessionFactoryClass = sessionFactory.getClass();
      m = sessionFactoryClass.getDeclaredMethod("getCurrentSession", new Class[0]);
      Object session = m.invoke(sessionFactory, new Object[0]);

      return session;
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    } catch (SecurityException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new TCRuntimeException(e);
    }
  }

  private Object createHibernateProxy(Class persistenceClass, String entityName, Class[] interfaces, Serializable id,
                                      Object session, Method getIdentifierMethod, Method setIdentifierMethod,
                                      Object componentIdType) {
    ClassLoader loader = persistenceClass.getClassLoader();

    try {
      Class lazyInitializerClass = loader.loadClass(CGLIB_LAZY_INITIALIZER_CLASS_NAME);
      Class abstractComponentTypeClass = loader.loadClass(ABSTRACT_COMPONENT_CLASS_NAME);
      Class sessionImplementorClass = loader.loadClass(SESSION_IMPLEMENTOR_CLASS_NAME);

      Method getProxyMethod = lazyInitializerClass.getDeclaredMethod("getProxy", new Class[] { String.class,
          Class.class, Class[].class, Method.class, Method.class, abstractComponentTypeClass, Serializable.class,
          sessionImplementorClass });
      getProxyMethod.setAccessible(true);
      return getProxyMethod.invoke(null, new Object[] { entityName, persistenceClass, interfaces, getIdentifierMethod,
          setIdentifierMethod, componentIdType, id, session });
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    } catch (SecurityException e) {
      throw new TCRuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalArgumentException e) {
      throw new TCRuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new TCRuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new TCRuntimeException(e);
    }
  }

  public Map connectedCopy(Object source, Object dest, Map visited, ClientObjectManager objectManager,
                           OptimisticTransactionManager txManager) {
    throw new TCNotSupportedMethodException();
  }
}
