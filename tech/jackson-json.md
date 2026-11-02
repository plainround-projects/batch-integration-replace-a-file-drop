# Jackson and JSON

## What it is

**JSON** (JavaScript Object Notation) is a text format for structured data: objects written `{"name": value, ...}`, lists written `[a, b]`, and the values text (in double quotes), numbers, `true`, `false` and `null`. It is the common format programs use to exchange data because people can read it and every language can parse it. **Jackson** is the standard Java library that converts between Java objects and JSON text. Turning an object into text is **serializing**; turning text into an object is **deserializing**. Jackson works by reflection: it looks at the fields or the record components of a class and uses their names as the JSON keys.

For example, the record `Order("N01O000001", "C0042", RETAIL, 123.45)` becomes `{"orderId":"N01O000001","customer":"C0042","type":"RETAIL","amount":123.45}`.

## Why this repository uses it

An order travels through RabbitMQ as the body of a message. A broker moves bytes, so the order must be turned into bytes on one side and back into an `Order` on the other. JSON is readable in the RabbitMQ management page, which helps a person debug a stuck queue. The conversion is done by Spring AMQP's `Jackson2JsonMessageConverter`, which uses Jackson.

## Where it appears

| Path | What it does with Jackson |
| --- | --- |
| `src/main/java/plainround/orderflow/infrastructure/amqp/RabbitTopology.java` | Declares `Jackson2JsonMessageConverter` as the message converter. |
| `src/main/java/plainround/orderflow/infrastructure/amqp/OrderListener.java` | Receives an `Order` already converted from JSON. |
| `src/main/java/plainround/orderflow/infrastructure/amqp/ConfirmedPublisher.java` | Sends an `Order` that is converted to JSON (and a dead letter as plain text). |
| `pom.xml` | `spring-boot-starter-json` brings Jackson. |
| `src/test/java/plainround/orderflow/integration/StoresAndBrokerIT.java` | Sends the broken body `{not json` to prove a bad message is rejected. |

## How it works underneath

Jackson's `ObjectMapper` reads a class's structure (for a record, its components; for a class, its getters and fields) and builds a **serializer** and a **deserializer** for it, cached for later use. To serialize, it walks the object and writes each property as `"name": value`. To deserialize, it parses the text into tokens, finds the constructor (for a record, the canonical constructor), and calls it with the values it read. Numbers with decimals become `BigDecimal` when the target type is `BigDecimal`, so the amount keeps its exact value. An enum is written as its constant name (`"RETAIL"`). If the text is not valid JSON, the parser throws an exception; Spring AMQP wraps it in a `MessageConversionException`, and the listener container treats that as a message that can never succeed and rejects it at once.

The converter also writes a **content type** header, `application/json`, and (by default) a `__TypeId__` header holding the Java class name. The receiving converter uses these to know what to build. Because the class name is in a header, the producer and the consumer need the same class on both sides.

## Walkthrough of this repository's use

```java
  private Queue order(String name, Map<String, Object> arguments) {
    return QueueBuilder.durable(name).withArguments(arguments).build();
  }

  @Bean
  MessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
```

The bean `messageConverter` is the only place JSON is chosen. Spring AMQP finds a bean of type `MessageConverter` and uses it for every `convertAndSend` and every `@RabbitListener`. `Order` is a Java record (the Java note), which Jackson 2.12 and later reads natively through its canonical constructor.

```java
  private final ProcessOrderMessage process;

  @RabbitListener(queues = {"retail", "wholesale"})
  public void onOrder(Order order) {
    process.handle(order);
  }
```

The listener method takes an `Order`, not a message: the converter has already turned the body into one. A body that cannot be turned into an `Order` never reaches this method.

```java
  @Test
  void
      a_message_that_cannot_be_read_is_rejected_to_the_rejected_queue_and_the_service_keeps_working() {
    try (ConfigurableApplicationContext context = TestStack.start(inbox, "order-service")) {
      RabbitAdmin admin = context.getBean(RabbitAdmin.class);
      RabbitTemplate template = context.getBean(RabbitTemplate.class);
      TestStack.purgeQueues(admin);
      template.send(
          "",
          "retail",
          MessageBuilder.withBody("{not json".getBytes())
              .setContentType("application/json")
              .build());
      Awaitility.await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> TestStack.depth(admin, "rejected") == 1);
```

The test builds a message by hand with the body `{not json` and the content type `application/json`, so the converter tries to parse it, fails, and the message is rejected into the queue `rejected`. The dead letter, in contrast, is published as plain text (the original line of the file), because it is by definition something that is not a valid order.

## Try it yourself

1. Find Jackson on the classpath: `./mvnw dependency:tree | grep jackson-databind`.
2. See an order as JSON: after `make apps partner`, open the RabbitMQ management page at `http://localhost:56722`, queue `retail`, "Get messages" (do not requeue off: choose "Nack message requeue true" so the message stays), and read the payload `{"orderId":...}`.
3. Try Jackson by hand in `jshell`: `jshell --class-path "$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes"`, then `new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new plainround.orderflow.domain.Order("O1","C1",plainround.orderflow.domain.OrderType.RETAIL,new java.math.BigDecimal("10.50")))` prints the JSON.
4. Read the headers of a message in the management page: look for `content_type: application/json`.
5. Send the poison message yourself: in the management page, "Publish message" to the queue `retail` with the payload `{not json` while the order service runs, and watch the queue `rejected` grow by one.

## Mistakes people make

- **Floating-point numbers for money in JSON.** Decode into `BigDecimal`; `double` would round.
- **Changing a class name or package on one side only.** The `__TypeId__` header then names a class the other side does not have.
- **Assuming a bad message will fix itself.** A message that cannot be parsed fails every time; reject it instead of retrying it.
- **Putting secrets in message bodies.** The management page shows them.

## Check yourself

1. What is serializing? *Turning an object into text (or bytes).*
2. What does Jackson use as the JSON keys of a record? *The names of its components.*
3. What happens to a body that is not valid JSON? *The converter throws, and the listener rejects the message without requeue.*
4. Why is the dead letter sent as plain text? *It is a refused line, not a valid order, so it has no object form.*
5. Why does the amount stay exact? *It is a `BigDecimal` on both sides, written as a JSON number.*

## Further reading

- Jackson databind: https://github.com/FasterXML/jackson-databind
- Spring AMQP message converters: https://docs.spring.io/spring-amqp/reference/3.2/amqp/message-converters.html
- JSON (ECMA-404): https://ecma-international.org/publications-and-standards/standards/ecma-404/
