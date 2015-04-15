/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package com.tc.util;

import com.tc.test.TCTestCase;

/**
 * Unit test for {@link EqualsEqualityComparator}.
 */
public class EqualsEqualityComparatorTest extends TCTestCase {

  private static boolean CASE_INSENSITIVE = false;

  public static class MyObj {
    private final String value;

    public MyObj(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) return true;
      if (!(that instanceof MyObj)) return false;

      if (CASE_INSENSITIVE) return this.value.equalsIgnoreCase(((MyObj) that).value);
      else return this.value.equals(((MyObj) that).value);
    }
  }

  public void testNull() {
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(null, null));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals("foo", null));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(null, "foo"));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(null, new Integer(0)));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(new Integer(0), null));
  }

  public void testObjects() {
    MyObj one = new MyObj("foo");
    MyObj two = new MyObj("foo");
    MyObj three = new MyObj("Foo");
    MyObj four = new MyObj("Foo");

    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(one, two));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(two, one));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(three, four));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(four, three));

    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(one, three));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(three, one));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(one, four));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(four, one));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(two, three));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(three, two));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(two, four));
    assertFalse(EqualsEqualityComparator.INSTANCE.isEquals(four, two));

    CASE_INSENSITIVE = true;

    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(one, two));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(two, one));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(three, four));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(four, three));

    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(one, three));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(three, one));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(one, four));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(four, one));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(two, three));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(three, two));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(two, four));
    assertTrue(EqualsEqualityComparator.INSTANCE.isEquals(four, two));
  }

}