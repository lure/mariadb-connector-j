// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.mariadb.jdbc.plugin.codec;

import java.io.IOException;
import java.sql.SQLDataException;
import java.time.*;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.EnumSet;
import org.mariadb.jdbc.client.Column;
import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.client.DataType;
import org.mariadb.jdbc.client.ReadableByteBuf;
import org.mariadb.jdbc.client.socket.Writer;
import org.mariadb.jdbc.plugin.Codec;

/** Instant codec */
public class InstantCodec implements Codec<Instant> {

  /** default instance */
  public static final InstantCodec INSTANCE = new InstantCodec();

  private static final EnumSet<DataType> COMPATIBLE_TYPES =
      EnumSet.of(
          DataType.DATETIME,
          DataType.DATE,
          DataType.YEAR,
          DataType.TIMESTAMP,
          DataType.VARSTRING,
          DataType.VARCHAR,
          DataType.STRING,
          DataType.TIME,
          DataType.BLOB,
          DataType.TINYBLOB,
          DataType.MEDIUMBLOB,
          DataType.LONGBLOB);

  public String className() {
    return Instant.class.getName();
  }

  public boolean canDecode(Column column, Class<?> type) {
    return COMPATIBLE_TYPES.contains(column.getType()) && type.isAssignableFrom(Instant.class);
  }

  public boolean canEncode(Object value) {
    return value instanceof Instant;
  }

  @Override
  public Instant decodeText(ReadableByteBuf buf, int length, Column column, Calendar calParam)
      throws SQLDataException {
    LocalDateTime localDateTime =
        LocalDateTimeCodec.INSTANCE.decodeText(buf, length, column, calParam);
    if (localDateTime == null) return null;
    return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }

  @Override
  public Instant decodeBinary(ReadableByteBuf buf, int length, Column column, Calendar calParam)
      throws SQLDataException {
    LocalDateTime localDateTime =
        LocalDateTimeCodec.INSTANCE.decodeBinary(buf, length, column, calParam);
    if (localDateTime == null) return null;
    return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }

  @Override
  public void encodeText(
      Writer encoder, Context context, Object val, Calendar calParam, Long maxLen)
      throws IOException {
    Instant instant = (Instant) val;

    encoder.writeByte('\'');
    ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
    if (calParam != null) {
      zonedDateTime = zonedDateTime.withZoneSameInstant(calParam.getTimeZone().toZoneId());
    }
    encoder.writeAscii(
        zonedDateTime.format(
            instant.getNano() != 0
                ? LocalDateTimeCodec.TIMESTAMP_FORMAT
                : LocalDateTimeCodec.TIMESTAMP_FORMAT_NO_FRACTIONAL));
    encoder.writeByte('\'');
  }

  @Override
  public void encodeBinary(Writer encoder, Object value, Calendar calParam, Long maxLength)
      throws IOException {
    Instant instant = (Instant) value;
    ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
    if (calParam != null) {
      zonedDateTime = zonedDateTime.withZoneSameInstant(calParam.getTimeZone().toZoneId());
    }

    int nano = zonedDateTime.getNano();
    if (nano > 0) {
      encoder.writeByte((byte) 11);
      encoder.writeShort((short) zonedDateTime.get(ChronoField.YEAR));
      encoder.writeByte(zonedDateTime.get(ChronoField.MONTH_OF_YEAR));
      encoder.writeByte(zonedDateTime.get(ChronoField.DAY_OF_MONTH));
      encoder.writeByte(zonedDateTime.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte(zonedDateTime.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte(zonedDateTime.get(ChronoField.SECOND_OF_MINUTE));
      encoder.writeInt(nano / 1000);
    } else {
      encoder.writeByte((byte) 7);
      encoder.writeShort((short) zonedDateTime.get(ChronoField.YEAR));
      encoder.writeByte(zonedDateTime.get(ChronoField.MONTH_OF_YEAR));
      encoder.writeByte(zonedDateTime.get(ChronoField.DAY_OF_MONTH));
      encoder.writeByte(zonedDateTime.get(ChronoField.HOUR_OF_DAY));
      encoder.writeByte(zonedDateTime.get(ChronoField.MINUTE_OF_HOUR));
      encoder.writeByte(zonedDateTime.get(ChronoField.SECOND_OF_MINUTE));
    }
  }

  public int getBinaryEncodeType() {
    return DataType.DATETIME.get();
  }
}
