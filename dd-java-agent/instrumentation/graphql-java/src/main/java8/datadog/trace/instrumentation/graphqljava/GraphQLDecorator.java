package datadog.trace.instrumentation.graphqljava;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class GraphQLDecorator extends BaseDecorator {
  public static final GraphQLDecorator DECORATE = new GraphQLDecorator();

  public static final CharSequence GRAPHQL_QUERY = UTF8BytesString.create("graphql.query");
  public static final CharSequence GRAPHQL_JAVA = UTF8BytesString.create("graphql-java");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"graphql-java"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.GRAPHQL;
  }

  @Override
  protected CharSequence component() {
    return GRAPHQL_JAVA;
  }
}
