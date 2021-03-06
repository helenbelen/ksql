/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.streams;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.ArithmeticBinaryExpression;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.ExecutionStepPropertiesV1;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.KGroupedStreamHolder;
import io.confluent.ksql.execution.plan.KGroupedTableHolder;
import io.confluent.ksql.execution.plan.KStreamHolder;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.plan.StreamAggregate;
import io.confluent.ksql.execution.plan.StreamFilter;
import io.confluent.ksql.execution.plan.StreamFlatMap;
import io.confluent.ksql.execution.plan.StreamGroupBy;
import io.confluent.ksql.execution.plan.StreamGroupByKey;
import io.confluent.ksql.execution.plan.StreamSelect;
import io.confluent.ksql.execution.plan.StreamSelectKey;
import io.confluent.ksql.execution.plan.StreamSelectKeyV1;
import io.confluent.ksql.execution.plan.StreamSource;
import io.confluent.ksql.execution.plan.StreamWindowedAggregate;
import io.confluent.ksql.execution.plan.TableAggregate;
import io.confluent.ksql.execution.plan.TableFilter;
import io.confluent.ksql.execution.plan.TableGroupBy;
import io.confluent.ksql.execution.plan.TableSelect;
import io.confluent.ksql.execution.plan.TableSource;
import io.confluent.ksql.execution.plan.WindowedStreamSource;
import io.confluent.ksql.execution.plan.WindowedTableSource;
import io.confluent.ksql.execution.windows.TumblingWindowExpression;
import io.confluent.ksql.execution.windows.WindowTimeClause;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.function.KsqlAggregateFunction;
import io.confluent.ksql.function.KsqlTableFunction;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.FunctionName;
import io.confluent.ksql.schema.Operator;
import io.confluent.ksql.schema.ksql.ColumnNames;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.SystemColumns;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.serde.WindowInfo;
import io.confluent.ksql.util.KsqlConfig;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.connect.data.Struct;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StepSchemaResolverTest {

  private static final LogicalSchema SCHEMA = LogicalSchema.builder()
      .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
      .valueColumn(ColumnName.of("ORANGE"), SqlTypes.INTEGER)
      .valueColumn(ColumnName.of("APPLE"), SqlTypes.BIGINT)
      .valueColumn(ColumnName.of("BANANA"), SqlTypes.STRING)
      .build();

  private static final ColumnName ORANGE_COL = ColumnName.of("ORANGE");

  private static final ExecutionStepPropertiesV1 PROPERTIES = new ExecutionStepPropertiesV1(
      new QueryContext.Stacker().getQueryContext()
  );

  @Mock
  private FunctionRegistry functionRegistry;
  @Mock
  private ExecutionStep<KStreamHolder<Struct>> streamSource;
  @Mock
  private ExecutionStep<KGroupedStreamHolder> groupedStreamSource;
  @Mock
  private ExecutionStep<KTableHolder<Struct>> tableSource;
  @Mock
  private ExecutionStep<KGroupedTableHolder> groupedTableSource;
  @Mock
  private Formats formats;
  @Mock
  private KsqlConfig config;

  private StepSchemaResolver resolver;

  @Before
  public void setup() {
    resolver = new StepSchemaResolver(config, functionRegistry);
  }

  @Test
  public void shouldResolveSchemaForStreamAggregate() {
    // Given:
    givenAggregateFunction("SUM", SqlTypes.BIGINT);
    final StreamAggregate step = new StreamAggregate(
        PROPERTIES,
        groupedStreamSource,
        formats,
        ImmutableList.of(ColumnName.of("ORANGE")),
        ImmutableList.of(functionCall("SUM", "APPLE"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("ORANGE"), SqlTypes.INTEGER)
            .valueColumn(ColumnNames.aggregateColumn(0), SqlTypes.BIGINT)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForStreamWindowedAggregate() {
    // Given:
    givenAggregateFunction("COUNT", SqlTypes.BIGINT);
    final StreamWindowedAggregate step = new StreamWindowedAggregate(
        PROPERTIES,
        groupedStreamSource,
        formats,
        ImmutableList.of(ColumnName.of("ORANGE")),
        ImmutableList.of(functionCall("COUNT", "APPLE")),
        new TumblingWindowExpression(new WindowTimeClause(10, TimeUnit.SECONDS))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("ORANGE"), SqlTypes.INTEGER)
            .valueColumn(ColumnNames.aggregateColumn(0), SqlTypes.BIGINT)
            .valueColumn(SystemColumns.WINDOWSTART_NAME, SystemColumns.WINDOWBOUND_TYPE)
            .valueColumn(SystemColumns.WINDOWEND_NAME, SystemColumns.WINDOWBOUND_TYPE)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForStreamSelect() {
    // Given:
    final StreamSelect<?> step = new StreamSelect<>(
        PROPERTIES,
        streamSource,
        ImmutableList.of(
            add("JUICE", "ORANGE", "APPLE"),
            ref("PLANTAIN", "BANANA"),
            ref("CITRUS", "ORANGE"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("JUICE"), SqlTypes.BIGINT)
            .valueColumn(ColumnName.of("PLANTAIN"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("CITRUS"), SqlTypes.INTEGER)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForStreamFlatMap() {
    // Given:
    givenTableFunction("EXPLODE", SqlTypes.DOUBLE);
    final StreamFlatMap<?> step = new StreamFlatMap<>(
        PROPERTIES,
        streamSource,
        ImmutableList.of(functionCall("EXPLODE", "BANANA"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("ORANGE"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("APPLE"), SqlTypes.BIGINT)
            .valueColumn(ColumnName.of("BANANA"), SqlTypes.STRING)
            .valueColumn(ColumnNames.synthesisedSchemaColumn(0), SqlTypes.DOUBLE)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForStreamFilter() {
    // Given:
    final StreamFilter<?> step = new StreamFilter<>(
        PROPERTIES,
        streamSource,
        mock(Expression.class)
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA));
  }

  @Test
  public void shouldResolveSchemaForStreamGroupBy() {
    // Given:
    final StreamGroupBy<?> step = new StreamGroupBy<>(
        PROPERTIES,
        streamSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.empty()
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(SystemColumns.ROWKEY_NAME, SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForStreamGroupByAnyKey() {
    // Given:
    when(config.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)).thenReturn(true);

    final StreamGroupBy<?> step = new StreamGroupBy<>(
        PROPERTIES,
        streamSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.empty()
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(ORANGE_COL, SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForAliasedStreamGroupBy() {
    // Given:
    when(config.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)).thenReturn(true);

    final StreamGroupBy<?> step = new StreamGroupBy<>(
        PROPERTIES,
        streamSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.of(ColumnName.of("NEW_KEY"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("NEW_KEY"), SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForStreamGroupByKey() {
    // Given:
    final StreamGroupByKey step = new StreamGroupByKey(
        PROPERTIES,
        streamSource,
        formats
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA));
  }

  @Test
  public void shouldResolveSchemaForStreamSelectKeyV1() {
    // Given:
    final Expression keyExpression =
        new UnqualifiedColumnReferenceExp(ColumnName.of("ORANGE"));

    final StreamSelectKeyV1 step = new StreamSelectKeyV1(
        PROPERTIES,
        streamSource,
        keyExpression
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(SystemColumns.ROWKEY_NAME, SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()
    ));
  }

  @Test
  public void shouldResolveSchemaForStreamSelectKeyV2() {
    // Given:
    final UnqualifiedColumnReferenceExp keyExpression =
        new UnqualifiedColumnReferenceExp(ColumnName.of("ORANGE"));

    final StreamSelectKey step = new StreamSelectKey(
        PROPERTIES,
        streamSource,
        keyExpression,
        Optional.empty()
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(keyExpression.getColumnName(), SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()
    ));
  }

  @Test
  public void shouldResolveSchemaForStreamSelectKeyV2WithAlias() {
    // Given:
    final UnqualifiedColumnReferenceExp keyExpression =
        new UnqualifiedColumnReferenceExp(ColumnName.of("ORANGE"));

    final StreamSelectKey step = new StreamSelectKey(
        PROPERTIES,
        streamSource,
        keyExpression,
        Optional.of(ColumnName.of("RED"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("RED"), SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()
    ));
  }

  @Test
  public void shouldResolveSchemaForStreamSource() {
    final StreamSource step = new StreamSource(
        PROPERTIES,
        "foo",
        formats,
        Optional.empty(),
        SCHEMA
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA.withPseudoAndKeyColsInValue(false)));
  }

  @Test
  public void shouldResolveSchemaForStreamWindowedSource() {
    final WindowedStreamSource step = new WindowedStreamSource(
        PROPERTIES,
        "foo",
        formats,
        WindowInfo.of(WindowType.TUMBLING, Optional.of(Duration.ofMillis(123))),
        Optional.empty(),
        SCHEMA
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA.withPseudoAndKeyColsInValue(true)));
  }

  @Test
  public void shouldResolveSchemaForTableAggregate() {
    // Given:
    givenAggregateFunction("SUM", SqlTypes.BIGINT);
    final TableAggregate step = new TableAggregate(
        PROPERTIES,
        groupedTableSource,
        formats,
        ImmutableList.of(ColumnName.of("ORANGE")),
        ImmutableList.of(functionCall("SUM", "APPLE"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("ORANGE"), SqlTypes.INTEGER)
            .valueColumn(ColumnNames.aggregateColumn(0), SqlTypes.BIGINT)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForTableGroupBy() {
    // Given:
    final TableGroupBy<?> step = new TableGroupBy<>(
        PROPERTIES,
        tableSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.empty()
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(SystemColumns.ROWKEY_NAME, SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForTableGroupByAnyKey() {
    // Given:
    when(config.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)).thenReturn(true);

    final TableGroupBy<?> step = new TableGroupBy<>(
        PROPERTIES,
        tableSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.empty()
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(ORANGE_COL, SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForAliasedTableGroupBy() {
    // Given:
    when(config.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)).thenReturn(true);

    final TableGroupBy<?> step = new TableGroupBy<>(
        PROPERTIES,
        tableSource,
        formats,
        ImmutableList.of(new UnqualifiedColumnReferenceExp(Optional.empty(), ORANGE_COL)),
        Optional.of(ColumnName.of("NEW_KEY"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(LogicalSchema.builder()
        .keyColumn(ColumnName.of("NEW_KEY"), SqlTypes.INTEGER)
        .valueColumns(SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldResolveSchemaForTableSelect() {
    // Given:
    final TableSelect<?> step = new TableSelect<>(
        PROPERTIES,
        tableSource,
        ImmutableList.of(
            add("JUICE", "ORANGE", "APPLE"),
            ref("PLANTAIN", "BANANA"),
            ref("CITRUS", "ORANGE"))
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(
        LogicalSchema.builder()
            .keyColumn(ColumnName.of("K0"), SqlTypes.INTEGER)
            .valueColumn(ColumnName.of("JUICE"), SqlTypes.BIGINT)
            .valueColumn(ColumnName.of("PLANTAIN"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("CITRUS"), SqlTypes.INTEGER)
            .build())
    );
  }

  @Test
  public void shouldResolveSchemaForTableFilter() {
    // Given:
    final TableFilter<?> step = new TableFilter<>(
        PROPERTIES,
        tableSource,
        mock(Expression.class)
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA));
  }

  @Test
  public void shouldResolveSchemaForTableSource() {
    // Given:
    final TableSource step = new TableSource(
        PROPERTIES,
        "foo",
        formats,
        Optional.empty(),
        SCHEMA
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA.withPseudoAndKeyColsInValue(false)));
  }

  @Test
  public void shouldResolveSchemaForWindowedTableSource() {
    // Given:
    final WindowedTableSource step = new WindowedTableSource(
        PROPERTIES,
        "foo",
        formats,
        mock(WindowInfo.class),
        Optional.empty(),
        SCHEMA
    );

    // When:
    final LogicalSchema result = resolver.resolve(step, SCHEMA);

    // Then:
    assertThat(result, is(SCHEMA.withPseudoAndKeyColsInValue(true)));
  }

  private void givenTableFunction(final String name, final SqlType returnType) {
    final KsqlTableFunction tableFunction = mock(KsqlTableFunction.class);
    when(functionRegistry.isTableFunction(FunctionName.of(name))).thenReturn(true);
    when(functionRegistry.getTableFunction(eq(FunctionName.of(name)), any()))
        .thenReturn(tableFunction);
    when(tableFunction.getReturnType(any())).thenReturn(returnType);
  }

  @SuppressWarnings("unchecked")
  private void givenAggregateFunction(final String name, final SqlType returnType) {
    final KsqlAggregateFunction aggregateFunction = mock(KsqlAggregateFunction.class);
    when(functionRegistry.getAggregateFunction(eq(FunctionName.of(name)), any(), any()))
        .thenReturn(aggregateFunction);
    when(aggregateFunction.getAggregateType()).thenReturn(SqlTypes.INTEGER);
    when(aggregateFunction.returnType()).thenReturn(returnType);
    when(aggregateFunction.getInitialValueSupplier()).thenReturn(mock(Supplier.class));
  }

  private static FunctionCall functionCall(final String name, final String column) {
    return new FunctionCall(
        FunctionName.of(name),
        ImmutableList.of(
            new UnqualifiedColumnReferenceExp(ColumnName.of(column)))
    );
  }

  private static SelectExpression add(final String alias, final String col1, final String col2) {
    return SelectExpression.of(
        ColumnName.of(alias),
        new ArithmeticBinaryExpression(
            Operator.ADD,
            new UnqualifiedColumnReferenceExp(ColumnName.of(col1)),
            new UnqualifiedColumnReferenceExp(ColumnName.of(col2))
        )
    );
  }

  private static SelectExpression ref(final String alias, final String col) {
    return SelectExpression.of(
        ColumnName.of(alias),
        new UnqualifiedColumnReferenceExp(ColumnName.of(col))
    );
  }
}