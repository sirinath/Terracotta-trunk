/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics;

import com.tc.util.Assert;

import java.io.Serializable;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This class structures the data that is retrieved through a
 * {@link StatisticRetrievalAction}.
 *
 * New instances are usually filled in through the specialized constructors.
 *
 * The {@code agentIp} and {@code agentDifferentiator} properties don't have
 * to be filled in. When an instance of {@code StatisticData} is stored in the
 * {@code StatisticsBuffer} of the CVT agent, these properties are set when
 * they are {@code null}. This should be the desired behavior is almost all of
 * the cases. Also, the {@code moment} property will be filled in by the
 * {@code StatisticsRetriever} to ensure that all the data in one retrieval
 * action can be correlated.
 *
 * Only four types of data can be stored within a {@code StatisticData} 
 * instance and they are mutually exclusive. The reason why it doesn't allow
 * any {@code Object} to be used as data is to allow for the CVT back-end to
 * store the data while preserving its type. This makes is easier to query
 * on the data values after collection.
 *
 * The {@code name} of a {@code StatisticData} instance should identify the
 * type of data that it contains, for instance "{@code cpu combined}". The
 * {@code element} property can be {@code null}, but when it's used it should
 * identify different elements of the same data. For instance when CPU data is
 * collected for multiple CPUs, the names are the same, but the elements will
 * be "{@code cpu 1}", "{@code cpu 2}", ... so that it's possible to identify
 * the individual data points.
 *
 * Finally, when {@link StatisticRetrievalAction}s return an array of
 * {@code StatisticData} instances, the {@code moment} property of each
 * individual data instance should be the same so that the entire array
 * can be situated at the same location on a timeline. Usually this is done
 * by creating a {@link Date} instance before instantiating the
 * {@code StatisticData} instances and passing that {@code Date} instance to
 * the constructor of each data element.
 */
public class StatisticData implements Serializable {
  public final static StatisticData[] EMPTY_ARRAY = new StatisticData[0];

  public final static String CURRENT_CSV_VERSION = "1.0";
  public final static String CURRENT_CSV_HEADER = "Session ID,IP,Differentiator,Moment,Name,Element,Data Number,Data Text,Data Date,Data Decimal\n";

  private final static long serialVersionUID = -3387790670840965825L;

  private String sessionId;
  private String agentIp;
  private String agentDifferentiator;
  private Date moment;
  private String name;
  private String element;
  private Object data;

  public StatisticData() {
  }
  
  public StatisticData(final String name, final Long value) {
    setName(name);
    setData(value);
  }

  public StatisticData(final String name, final String value) {
    setName(name);
    setData(value);
  }

  public StatisticData(final String name, final Date value) {
    setName(name);
    setData(value);
  }

  public StatisticData(final String name, final BigDecimal value) {
    setName(name);
    setData(value);
  }

  public StatisticData(final String name, final String element, final Long value) {
    setName(name);
    setElement(element);
    setData(value);
  }

  public StatisticData(final String name, final String element, final String value) {
    setName(name);
    setElement(element);
    setData(value);
  }

  public StatisticData(final String name, final String element, final Date value) {
    setName(name);
    setElement(element);
    setData(value);
  }

  public StatisticData(final String name, final String element, final BigDecimal value) {
    setName(name);
    setElement(element);
    setData(value);
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public StatisticData sessionId(String sessionId) {
    setSessionId(sessionId);
    return this;
  }

  public String getAgentIp() {
    return agentIp;
  }

  public void setAgentIp(String agentIp) {
    this.agentIp = agentIp;
  }

  public StatisticData agentIp(String agentIp) {
    setAgentIp(agentIp);
    return this;
  }

  public String getAgentDifferentiator() {
    return agentDifferentiator;
  }

  public StatisticData agentDifferentiator(String agentDifferentiator) {
    setAgentDifferentiator(agentDifferentiator);
    return this;
  }

  public void setAgentDifferentiator(String agentDifferentiator) {
    this.agentDifferentiator = agentDifferentiator;
  }

  public void setMoment(Date moment) {
    this.moment = moment;
  }

  public StatisticData moment(Date moment) {
    setMoment(moment);
    return this;
  }

  public Date getMoment() {
    return moment;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public StatisticData name(String name) {
    setName(name);
    return this;
  }

  public String getElement() {
    return element;
  }

  public void setElement(String element) {
    this.element = element;
  }

  public StatisticData element(String element) {
    setElement(element);
    return this;
  }

  public Object getData() {
    return data;
  }

  private void setData(Object data) {
    this.data = data;
  }

  private StatisticData data(Object data) {
    setData(data);
    return this;
  }

  public void setData(Long data) {
    setData((Object)data);
  }

  public StatisticData data(Long data) {
    return data((Object)data);
  }

  public void setData(String data) {
    setData((Object)data);
  }

  public StatisticData data(String data) {
    return data((Object)data);
  }

  public void setData(Date data) {
    setData((Object)data);
  }

  public StatisticData data(Date data) {
    return data((Object)data);
  }

  public void setData(BigDecimal data) {
    setData((Object)data);
  }

  public StatisticData data(BigDecimal data) {
    return data((Object)data);
  }

  public Object clone() {
    return new StatisticData()
      .sessionId(sessionId)
      .agentIp(agentIp)
      .agentDifferentiator(agentDifferentiator)
      .moment(moment)
      .name(name)
      .element(element)
      .data(data);
  }

  public String toString() {
    DateFormat format = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss SSS");
    String data_formatted;
    if (data != null &&
        data instanceof Date) {
      data_formatted = format.format(data);
    } else {
      data_formatted = String.valueOf(data);
    }
    return "["
           + "sessionId = " + sessionId + "; "
           + "agentIp = " + agentIp + "; "
           + "agentDifferentiator = " + agentDifferentiator + "; "
           + "moment = " + (null == moment ? String.valueOf(moment): format.format(moment)) + "; "
           + "name = " + name + "; "
           + "element = " + element + "; "
           + "data = " + data_formatted + ""
           + "]";
  }

  private static String escapeForCsv(final String value) {
    char[] chars = value.toCharArray();
    StringBuffer buffer = null;
    int last = 0;
    String replacement = null;
    for (int i = 0; i < chars.length; i++) {
      switch (chars[i]) {
        case '\\':
          replacement = "\\\\";
          break;
        case '"':
          replacement = "\\\"";
          break;
        case '\r':
          replacement = "";
          break;
        case '\n':
          replacement = "\\n";
          break;
      }

      if (replacement != null) {
        if (null == buffer) {
          buffer = new StringBuffer();
        }

        if (last < i) {
          buffer.append(chars, last, i - last);
        }

        buffer.append(replacement);
        replacement = null;
        last = i+1;
      }
    }

    if (null == buffer) {
      return value;
    }

    if (last < value.length()) {
      buffer.append(chars, last, value.length() - last);
    }

    return buffer.toString();
  }

  private static void addCsvField(final StringBuffer result, final Object field, final boolean separator) {
    if (null == field) {
      if (separator) {
        result.append(",");
      }
    } else {
      result.append("\"");
      result.append(escapeForCsv(String.valueOf(field)));
      result.append("\"");
      if (separator) {
        result.append(",");
      }
    }
  }

  public String toCsv() {
    StringBuffer result = new StringBuffer();
    addCsvField(result, sessionId, true);
    addCsvField(result, agentIp, true);
    addCsvField(result, agentDifferentiator, true);
    addCsvField(result, (null == moment ? null : new Long((moment).getTime())), true);
    addCsvField(result, name, true);
    addCsvField(result, element, true);
    if (null == data) {
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, null, false);
    } else if (data instanceof BigDecimal) {
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, data, false);
    } else if (data instanceof Number) {
      addCsvField(result, data, true);
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, null, false);
    } else if (data instanceof CharSequence) {
      addCsvField(result, null, true);
      addCsvField(result, data, true);
      addCsvField(result, null, true);
      addCsvField(result, null, false);
    } else if (data instanceof Date) {
      addCsvField(result, null, true);
      addCsvField(result, null, true);
      addCsvField(result, (null == data ? null : new Long(((Date)data).getTime())), true);
      addCsvField(result, null, false);
    }
    result.append("\n");
    return result.toString();
  }

  public static StatisticData newInstanceFromCsvLine(final String dataFormatVersion, final String line) throws ParseException {
    Assert.assertNotNull("dataFormatVersion", dataFormatVersion);

    if (CURRENT_CSV_VERSION.equals(dataFormatVersion)) {
      return new StatisticDataCSVParser(line).parse();
    } else {
      throw new ParseException("The data format version '" + dataFormatVersion + "' is not supported.", 0);
    }
  }

  private static class StatisticDataCSVParser {
    private final String line;
    private int position = 0;

    public StatisticDataCSVParser(final String line) {
      this.line = line;
    }

    private char getNextChar() {
      if (position == line.length()) {
        return 0;
      }
      return line.charAt(position++);
    }

    private void setField(final StatisticData data, final int position, final String value) throws ParseException {
      if (null == value) {
        return;
      }
      switch (position) {
        case 0:
          data.setSessionId(value);
          break;
        case 1:
          data.setAgentIp(value);
          break;
        case 2:
          data.setAgentDifferentiator(value);
          break;
        case 3:
          data.setMoment(new Date(Long.parseLong(value)));
          break;
        case 4:
          data.setName(value);
          break;
        case 5:
          data.setElement(value);
          break;
        case 6:
          data.setData(new Long(Long.parseLong(value)));
          break;
        case 7:
          data.setData(value);
          break;
        case 8:
          data.setData(new Date(Long.parseLong(value)));
          break;
        case 9:
          data.setData(new BigDecimal(value));
          break;
      }
    }

    public StatisticData parse() throws ParseException {
      final StatisticData data = new StatisticData();

      int field_count = 0;
      String field = null;
      // separates into fields
      fieldloop:
      while (true) {
        char ch = getNextChar();
        switch (ch) {
          case 0:
          case '\n':
          case '\r':
            setField(data, field_count, field);
            field = null;
            break fieldloop;
          case ' ':
          case '\t':
            continue;
          case ',':
            setField(data, field_count, field);
            field = null;
            field_count++;
            break;
          case '"':
            final StringBuffer buffer = new StringBuffer();
            synchronized (buffer) {
              // retrieve the value of a single field
              valueloop:
              while (true) {
                ch = getNextChar();
                switch (ch) {
                  case '\\':
                    ch = getNextChar();
                    switch (ch) {
                      case 'n':
                        buffer.append('\n');
                        break;
                      case '"':
                      case '\\':
                        buffer.append(ch);
                        break;
                    }
                    break;
                  case '"':
                    break valueloop;
                  case 0:
                  case '\n':
                  case '\r':
                    throw new ParseException("Unexpected line ending.", position);
                  default:
                    buffer.append(ch);
                    break;
                }
              }

              field = buffer.toString();
              break;
            }
          default:
            if (ch <= ' ') {
              continue;
            } else {
              throw new ParseException("Unexpected character '" + ch + "'", position);
            }
        }
      }

      return data;
    }
  }
}