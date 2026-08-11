/**
 * Serial prototype for the Fireline parallel-programming assignment.
 *
 * The program runs either a deterministic heat-diffusion simulation or a
 * simplified deterministic wildfire simulation on a heterogeneous 2D
 * landscape. The core update is a four-neighbour stencil calculation using
 * double buffering.
 *
 * Usage:
 *   java FirelineSerial <rows> <columns> <seed> <mode> <output-prefix>
 *                       [max-steps] [tolerance] [landscape]
 *                       [ignition-top-row ignition-left-column patch-size]
 *
 * Example:
 *   java FirelineSerial 300 300 42 wildfire output/fireline
 */
public class FirelineSerial {

    private static final int DEFAULT_MAXIMUM_STEPS = 5_000;
    private static final double DEFAULT_TOLERANCE = 0.05;

    public static void main(String[] args) {
        if (args.length < 5 || args.length > 11 || (args.length > 8 && args.length < 11)) {
            printUsage();
            System.exit(1);
        }

        try {
            int rows = parsePositiveInteger(args[0], "rows");
            int columns = parsePositiveInteger(args[1], "columns");
            long seed = Long.parseLong(args[2]);
            FireMap.Mode mode = FireMap.Mode.fromString(args[3]);
            String outputPrefix = args[4].trim();
            int maximumSteps = args.length >= 6
                    ? parsePositiveInteger(args[5], "maximum steps")
                    : DEFAULT_MAXIMUM_STEPS;
            double tolerance = args.length >= 7
                    ? parsePositiveDouble(args[6], "tolerance")
                    : DEFAULT_TOLERANCE;
            FireMap.Landscape landscape = args.length >= 8
                    ? FireMap.Landscape.fromString(args[7])
                    : FireMap.Landscape.MIXED;

            Integer ignitionTopRow = null;
            Integer ignitionLeftColumn = null;
            Integer ignitionPatchSize = null;
            if (args.length == 11) {
                ignitionTopRow = parseNonNegativeInteger(
                        args[8], "ignition top row");
                ignitionLeftColumn = parseNonNegativeInteger(
                        args[9], "ignition left column");
                ignitionPatchSize = parsePositiveInteger(
                        args[10], "ignition patch size");
            }

            if (outputPrefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "The output prefix may not be empty.");
            }

            FireMap map = new FireMap(
                    rows, columns, seed, mode, landscape,
                    ignitionTopRow, ignitionLeftColumn, ignitionPatchSize);

            long startTime = System.nanoTime();
            FireMap.StepResult result = null;
            int stepsCompleted = 0;
            boolean converged = false;

            while (stepsCompleted < maximumSteps) {
                result = map.step(mode);
                stepsCompleted++;

                if (mode == FireMap.Mode.WILDFIRE) {
                    converged = result.getBurningCells() == 0
                            && result.getMaximumTemperatureChange() < tolerance;
                } else {
                    converged = result.getMaximumTemperatureChange() < tolerance;
                }

                if (converged) {
                    break;
                }
            }

            long endTime = System.nanoTime();
            double elapsedMilliseconds = (endTime - startTime) / 1_000_000.0;

            map.writeImages(outputPrefix);

            System.out.println("Fireline serial simulation");
            System.out.printf("Mode: %s%n", mode.name().toLowerCase());
            System.out.printf("Rows: %d, Columns: %d%n", rows, columns);
            System.out.printf("Random seed: %d%n", seed);
            System.out.printf("Landscape: %s%n",
                    landscape.name().toLowerCase());
            System.out.printf("Initial source: %s%n",
                    map.getSourceDescription());
            System.out.printf("Timesteps completed: %d%n", stepsCompleted);
            System.out.printf("Converged: %s%n", converged ? "yes" : "no");
            System.out.printf("Final burning cells: %d%n",
                    result == null ? 0 : result.getBurningCells());
            System.out.printf("Cells burned: %d%n", map.countBurnedCells());
            System.out.printf("Maximum peak temperature: %.3f%n",
                    map.getMaximumPeakTemperature());
            System.out.printf("Maximum change in final timestep: %.6f%n",
                    result == null
                            ? 0.0
                            : result.getMaximumTemperatureChange());
            System.out.printf("Core simulation time: %.3f ms%n",
                    elapsedMilliseconds);
            System.out.printf("Images written with prefix: %s%n", outputPrefix);

            if (!converged) {
                System.out.println(
                        "Warning: maximum timestep limit reached before convergence.");
            }

        } catch (NumberFormatException exception) {
            System.err.println("Invalid numeric argument: " + exception.getMessage());
            printUsage();
            System.exit(1);
        } catch (IllegalArgumentException exception) {
            System.err.println("Input error: " + exception.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("Simulation failed: " + exception.getMessage());
            exception.printStackTrace();
            System.exit(1);
        }
    }

    private static int parsePositiveInteger(String value, String name) {
        int result = Integer.parseInt(value);
        if (result <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return result;
    }

    private static int parseNonNegativeInteger(String value, String name) {
        int result = Integer.parseInt(value);
        if (result < 0) {
            throw new IllegalArgumentException(
                    name + " must be zero or greater.");
        }
        return result;
    }

    private static double parsePositiveDouble(String value, String name) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result) || result <= 0.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value greater than zero.");
        }
        return result;
    }

    private static void printUsage() {
        System.err.println(
                "Usage: java FirelineSerial <rows> <columns> <seed> "
                + "<diffusion|wildfire> <output-prefix> "
                + "[max-steps] [tolerance] [mixed|grass] "
                + "[ignition-top-row ignition-left-column patch-size]");
        System.err.println("Examples:");
        System.err.println(
                "  java FirelineSerial 300 300 42 wildfire "
                + "output/fireline");
        System.err.println(
                "  java FirelineSerial 2000 2000 17 wildfire "
                + "output/benchmark 50000 0.05 grass 20 20 9");
    }

}
