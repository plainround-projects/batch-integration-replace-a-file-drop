package plainround.orderflow.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The pretend legacy partner (the lesson's {@code partner.py}). A file of 10,000 order lines, three
 * planted bad ones (no amount, negative amount, unknown type) and one repeated line. {@code night}
 * makes the order ids of different files different, so a week of files is a week of distinct
 * orders.
 */
public class GeneratePartnerFile {

  private static final int ORDERS = 10_000;

  /**
   * Writes the file; returns the number of valid, distinct orders it holds (9,997 for the default
   * shape).
   */
  public int write(Path target, int night, long seed) {
    Random random = new Random(seed);
    List<String> lines = new ArrayList<>(List.of("order_id,customer,type,amount"));
    IntStream.rangeClosed(1, ORDERS)
        .mapToObj(number -> order(night, number, random))
        .forEach(lines::add);
    plantBadLines(lines, night);
    lines.add(lines.get(11));
    write(target, lines);
    return ORDERS - 3;
  }

  private String order(int night, int number, Random random) {
    String type = random.nextBoolean() ? "retail" : "wholesale";
    BigDecimal amount =
        BigDecimal.valueOf(5 + random.nextDouble() * 895).setScale(2, RoundingMode.HALF_UP);
    return "N%02dO%06d,C%04d,%s,%s".formatted(night, number, 1 + random.nextInt(500), type, amount);
  }

  private void plantBadLines(List<String> lines, int night) {
    lines.set(500, "N%02dX999001,C0001,retail,".formatted(night));
    lines.set(2500, "N%02dX999002,C0002,retail,-4".formatted(night));
    lines.set(7500, "N%02dX999003,C0003,barter,10".formatted(night));
  }

  private void write(Path target, List<String> lines) {
    try {
      Files.write(target, lines);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
