package dev.morphia.aggregation.experimental.expressions.impls;

import dev.morphia.Datastore;
import dev.morphia.aggregation.experimental.expressions.TimeUnit;
import org.bson.BsonWriter;
import org.bson.codecs.EncoderContext;

import java.util.Locale;

import static dev.morphia.aggregation.experimental.codecs.ExpressionHelper.document;
import static dev.morphia.aggregation.experimental.codecs.ExpressionHelper.expression;

/**
 * Increments a Date object by a specified number of time units.
 *
 * @mongodb.server.release 5.0
 * @aggregation.expression $dateAdd
 * @since 2.3
 */
public class DateAddExpression extends Expression {
    private final Expression startDate;
    private final long amount;
    private final TimeUnit unit;
    private Expression timezone;

    public DateAddExpression(Expression startDate, long amount, TimeUnit unit) {
        super("$dateAdd");
        this.startDate = startDate;
        this.amount = amount;
        this.unit = unit;
    }

    @Override
    public void encode(Datastore datastore, BsonWriter writer, EncoderContext encoderContext) {
        document(writer, getOperation(), () -> {
            expression(datastore, writer, "startDate", startDate, encoderContext);
            writer.writeString("unit", unit.name().toLowerCase(Locale.ROOT));
            writer.writeInt64("amount", amount);
            expression(datastore, writer, "timezone", timezone, encoderContext);
        });
    }

    /**
     * The timezone to carry out the operation. <tzExpression> must be a valid expression that resolves to a string formatted as either
     * an Olson Timezone Identifier or a UTC Offset. If no timezone is provided, the result is displayed in UTC.
     *
     * @param timezone the timezone expression
     * @return this
     * @since 2.3
     */
    public DateAddExpression timezone(Expression timezone) {
        this.timezone = timezone;
        return this;
    }
}