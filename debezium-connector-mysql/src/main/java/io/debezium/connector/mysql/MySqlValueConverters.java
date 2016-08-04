/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;

import com.github.shyiko.mysql.binlog.event.deserialization.AbstractRowsEventDataDeserializer;

import io.debezium.annotation.Immutable;
import io.debezium.jdbc.JdbcValueConverters;
import io.debezium.relational.Column;
import io.debezium.relational.ValueConverter;
import io.debezium.time.Date;
import io.debezium.time.Year;

/**
 * MySQL-specific customization of the conversions from JDBC values obtained from the MySQL binlog client library.
 * <p>
 * This class always uses UTC for the default time zone when converting values without timezone information to values that require
 * timezones. This is because MySQL {@code TIMESTAMP} values are always
 * <a href="https://dev.mysql.com/doc/refman/5.7/en/datetime.html">stored in UTC</a> (unlike {@code DATETIME} values) and
 * are replicated in this form. Meanwhile, the MySQL Binlog Client library will {@link AbstractRowsEventDataDeserializer
 * deserialize} these as {@link java.sql.Timestamp} values that have no timezone and, therefore, are presumed to be in UTC.
 * When the column is properly marked with a {@link Types#TIMESTAMP_WITH_TIMEZONE} type, the converters will need to convert
 * that {@link java.sql.Timestamp} value into an {@link OffsetDateTime} using the default time zone, which always is UTC.
 * 
 * @author Randall Hauch
 * @see com.github.shyiko.mysql.binlog.event.deserialization.AbstractRowsEventDataDeserializer
 */
@Immutable
public class MySqlValueConverters extends JdbcValueConverters {

    /**
     * Create a new instance that always uses UTC for the default time zone when converting values without timezone information
     * to values that require timezones.
     * <p>
     */
    public MySqlValueConverters() {
        super();
    }

    /**
     * Create a new instance, and specify the time zone offset that should be used only when converting values without timezone
     * information to values that require timezones. This default offset should not be needed when values are highly-correlated
     * with the expected SQL/JDBC types.
     * 
     * @param defaultOffset the zone offset that is to be used when converting non-timezone related values to values that do
     *            have timezones; may be null if UTC is to be used
     */
    public MySqlValueConverters(ZoneOffset defaultOffset) {
        super(defaultOffset);
    }

    @Override
    public SchemaBuilder schemaBuilder(Column column) {
        // Handle a few MySQL-specific types based upon how they are handled by the MySQL binlog client ...
        String typeName = column.typeName().toUpperCase();
        if (matches(typeName, "YEAR")) {
            return Year.builder();
        }
        if (matches(typeName, "ENUM")) {
            return SchemaBuilder.int32();
        }
        if (matches(typeName, "SET")) {
            return SchemaBuilder.int64();
        }
        // Otherwise, let the base class handle it ...
        return super.schemaBuilder(column);
    }

    @Override
    public ValueConverter converter(Column column, Field fieldDefn) {
        // Handle a few MySQL-specific types based upon how they are handled by the MySQL binlog client ...
        String typeName = column.typeName().toUpperCase();
        if (matches(typeName, "YEAR")) {
            return (data) -> convertYear(column, fieldDefn, data);
        }
        if (matches(typeName, "ENUM")) {
            return (data) -> convertInteger(column, fieldDefn, data);
        }
        if (matches(typeName, "SET")) {
            return (data) -> convertDouble(column, fieldDefn, data);
        }
        // Otherwise, let the base class handle it ...
        return super.converter(column, fieldDefn);
    }

    /**
     * Converts a value object for a MySQL {@code YEAR}, which appear in the binlog as an integer though returns from
     * the MySQL JDBC driver as either a short or a {@link java.sql.Date}.
     * 
     * @param column the column definition describing the {@code data} value; never null
     * @param fieldDefn the field definition; never null
     * @param data the data object to be converted into a {@link Date Kafka Connect date} type; never null
     * @return the converted value, or null if the conversion could not be made
     */
    @SuppressWarnings("deprecation")
    protected Object convertYear(Column column, Field fieldDefn, Object data) {
        if (data == null) return null;
        if ( data instanceof java.time.Year ) {
            // The MySQL binlog always returns a Year object ...
            return ((java.time.Year)data).getValue();
        }
        if ( data instanceof java.sql.Date ) {
            // MySQL JDBC driver sometimes returns a Java SQL Date object ...
            return ((java.sql.Date)data).getYear();
        }
        if (data instanceof Number) {
            // MySQL JDBC driver sometimes returns a short ...
            return ((Number)data).intValue();
        }
        return handleUnknownData(column, fieldDefn, data);
    }

    /**
     * Determine if the uppercase form of a column's type exactly matches or begins with the specified prefix.
     * Note that this logic works when the column's {@link Column#typeName() type} contains the type name followed by parentheses.
     * 
     * @param upperCaseTypeName the upper case form of the column's {@link Column#typeName() type name}
     * @param upperCaseMatch the upper case form of the expected type or prefix of the type; may not be null
     * @return {@code true} if the type matches the specified type, or {@code false} otherwise
     */
    protected boolean matches(String upperCaseTypeName, String upperCaseMatch) {
        if (upperCaseTypeName == null) return false;
        return upperCaseMatch.equals(upperCaseTypeName) || upperCaseTypeName.startsWith(upperCaseMatch + "(");
    }

}
