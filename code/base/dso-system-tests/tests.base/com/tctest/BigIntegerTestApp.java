/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest;

import com.tc.exception.TCRuntimeException;
import com.tc.object.TCObject;
import com.tc.object.bytecode.ByteCodeUtil;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tctest.runner.AbstractTransparentApp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.Random;

public class BigIntegerTestApp extends AbstractTransparentApp {
  private final static BigInteger refInt = new BigInteger("100");

  public BigIntegerTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
  }

  public void run() {
    invokeAllBigIntegerConstructors();
    invokeAllBigIntegerMethods();
  }
  
  private void invokeAllBigIntegerMethods() {
    Class bClazz = BigInteger.class;
    Method[] methods = bClazz.getDeclaredMethods();
    for (int i=0; i<methods.length; i++) {
      try {
        System.out.println("Method name: " + methods[i].getName());
        if (!Modifier.isPrivate(methods[i].getModifiers()) &&
            !methods[i].getName().startsWith(ByteCodeUtil.TC_METHOD_PREFIX) &&
            !methods[i].getName().endsWith("class$")) {
          System.out.println("Executing method: " + methods[i].getName());
          if (!Modifier.isPublic(methods[i].getModifiers())) {
            methods[i].setAccessible(true);
          }
          invokeMethod(methods[i]);
        }
      } catch (IllegalArgumentException e) {
        throw new TCRuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new TCRuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new TCRuntimeException(e);
      }
    }
  }
  
  private void invokeMethod(Method method) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException {
    BigInteger bigInteger = new BigInteger("101");
    method.invoke(bigInteger, getMethodArguments(method));
  }
  
  private Object[] getMethodArguments(Method method) {
    Object[] arguments = new Object[method.getParameterTypes().length];
    String methodName = method.getName();
    if ("add".equals(methodName) || "and".equals(methodName) || "andNot".equals(methodName)) {
      arguments[0] = new BigInteger("200");
    } else if ("clearBit".equals(methodName)) {
      arguments[0] = new Integer(1);
    } else if ("compareTo".equals(methodName) || "divide".equals(methodName) ||
        "divideAndRemainder".equals(methodName) || "equals".equals(methodName) ||
        "gcd".equals(methodName) || "max".equals(methodName) || "min".equals(methodName) ||
        "mod".equals(methodName) || "modInverse".equals(methodName) ||
        "multiply".equals(methodName) || "or".equals(methodName) || "remainder".equals(methodName) ||
        "subtract".equals(methodName) || "xor".equals(methodName)) {
      arguments[0] = refInt;
    } else if ("flipBit".equals(methodName) || "isProbablePrime".equals(methodName) ||
        "pow".equals(methodName) || "setBit".equals(methodName) || "shiftLeft".equals(methodName) ||
        "shiftRight".equals(methodName) || "testBit".equals(methodName) || "trailingZeroCnt".equals(methodName) ||
        "bitLen".equals(methodName) || "primeToCertainty".equals(methodName) || "bitCnt".equals(methodName)) {
      arguments[0] = new Integer(1);
    } else if ("modPow".equals(methodName)) {
      arguments[0] = refInt;
      arguments[1] = refInt;
    } else if ("probablePrime".equals(methodName)) {
      arguments[0] = new Integer(5);
      arguments[1] = new Random();
    } else if ("toString".equals(methodName) && arguments.length == 1) {
      arguments[0] = new Integer(1);
    } else if ("valueOf".equals(methodName)) {
      arguments[0] = new Long(1);
    } else if ("mulAdd".equals(methodName)) {
      arguments[0] = new int[]{ 1 };
      arguments[1] = new int[]{ 1 };
      arguments[2] = new Integer(0);
      arguments[3] = new Integer(1);
      arguments[4] = new Integer(1);
    } else if ("addOne".equals(methodName)) {
      arguments[0] = new int[]{ 1 };
      arguments[1] = new Integer(0);
      arguments[2] = new Integer(0);
      arguments[3] = new Integer(1);
    } else if ("primitiveLeftShift".equals(methodName) || "primitiveRightShift".equals(methodName)) {
      arguments[0] = new int[]{ 1 };
      arguments[1] = new Integer(1);
      arguments[2] = new Integer(1);
    } else if ("javaIncrement".equals(methodName)) {
      arguments[0] = new int[]{ 1 };
    } else if ("jacobiSymbol".equals(methodName)) {
      arguments[0] = new Integer(1);
      arguments[1] = refInt;
    }
    
    return arguments;
  }

  private void invokeAllBigIntegerConstructors() {
    Class bClazz = BigInteger.class;
    Constructor[] constructors = bClazz.getConstructors();
    for (int i=0; i<constructors.length; i++) {
      try {
        invokeConstructor(constructors[i]);
      } catch (IllegalArgumentException e) {
        throw new TCRuntimeException(e);
      } catch (InstantiationException e) {
        throw new TCRuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new TCRuntimeException(e);
      } catch (InvocationTargetException e) {
        throw new TCRuntimeException(e);
      }
    }
  }
  
  private void invokeConstructor(Constructor constructor) throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
    Class[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes[0] != TCObject.class) {
      constructor.newInstance(getArguments(parameterTypes));
    }
  }
  
  private Object[] getArguments(Class[] parameterTypes) {
    Object[] arguments = new Object[parameterTypes.length];
    switch (parameterTypes.length) {
      case 1:
        if (parameterTypes[0] == String.class) {
          arguments[0] = "100";
        }
        else {
          arguments[0] = new byte[]{(byte) 100};
        }
        break;
      case 2:
        if (parameterTypes[0] == String.class) {
          arguments[0] = "100";
          arguments[1] = new Integer(10);
        }
        else if (parameterTypes[1] == Random.class) {
          arguments[0] = new Integer(10);
          arguments[1] = new Random();
        }
        else {
          arguments[0] = new Integer(refInt.signum());
          arguments[1] = new byte[]{(byte) 100};
        }
        break;
      case 3:
        arguments[0] = new Integer(refInt.bitLength());
        arguments[1] = new Integer(5);
        arguments[2] = new Random();
        break;
    }

    return arguments;
  }
}
