import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Stores the landscape and dynamic state for a deterministic heat-diffusion
 * and wildfire simulation.
 *
 * Each timestep reads only from the current state arrays and writes only to
 * the next state arrays. The two sets of arrays are swapped after every full
 * grid update. This double-buffered design is important for both correctness
 * and later parallelisation.
 */
public class FireMap {

    public enum Mode {
        DIFFUSION,
        WILDFIRE;

        public static Mode fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Simulation mode may not be null.");
            }
            if (value.equalsIgnoreCase("diffusion")) {
                return DIFFUSION;
            }
            if (value.equalsIgnoreCase("wildfire")) {
                return WILDFIRE;
            }
            throw new IllegalArgumentException(
                    "Unknown mode '" + value + "'. Use 'diffusion' or 'wildfire'.");
        }
    }


    public enum Landscape {
        MIXED,
        GRASS;

        public static Landscape fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Landscape may not be null.");
            }
            if (value.equalsIgnoreCase("mixed")) {
                return MIXED;
            }
            if (value.equalsIgnoreCase("grass")) {
                return GRASS;
            }
            throw new IllegalArgumentException(
                    "Unknown landscape '" + value
                    + "'. Use 'mixed' or 'grass'.");
        }
    }

    /** Result returned by one complete serial timestep. */
    public static final class StepResult {
        private final double maximumTemperatureChange;
        private final int burningCells;
        private final int newlyIgnitedCells;

        public StepResult(double maximumTemperatureChange,
                          int burningCells,
                          int newlyIgnitedCells) {
            this.maximumTemperatureChange = maximumTemperatureChange;
            this.burningCells = burningCells;
            this.newlyIgnitedCells = newlyIgnitedCells;
        }

        public double getMaximumTemperatureChange() {
            return maximumTemperatureChange;
        }

        public int getBurningCells() {
            return burningCells;
        }

        public int getNewlyIgnitedCells() {
            return newlyIgnitedCells;
        }

        /** Combines the reductions produced by two disjoint grid regions. */
        public static StepResult combine(
                StepResult first, StepResult second) {
            return new StepResult(
                    Math.max(first.maximumTemperatureChange,
                            second.maximumTemperatureChange),
                    first.burningCells + second.burningCells,
                    first.newlyIgnitedCells + second.newlyIgnitedCells);
        }
    }

    public static final double AMBIENT_TEMPERATURE = 20.0;
    public static final double INITIAL_FIRE_TEMPERATURE = 400.0;
    public static final double FIXED_SOURCE_TEMPERATURE = 600.0;
    public static final double MAXIMUM_TEMPERATURE = 1000.0;

    private static final double FUEL_EPSILON = 1.0e-12;

    /*
     * Fixed temperature stops used by all peak-temperature images. Keeping
     * the scale fixed makes images from different runs directly comparable.
     */
    private static final double[] HEAT_TEMPERATURE_STOPS = {
        20.0, 40.0, 80.0, 150.0, 300.0, 500.0, 750.0, 1000.0
    };

    private static final Color[] HEAT_COLORS = {
        new Color(0, 0, 0),
        new Color(0, 20, 70),
        new Color(65, 0, 120),
        new Color(190, 0, 35),
        new Color(255, 95, 0),
        new Color(255, 210, 0),
        new Color(255, 245, 150),
        Color.WHITE
    };

    private final int rows;
    private final int columns;
    private final TerrainType[][] terrain;
    private final boolean[][] fixedHeatSource;
    private final boolean[][] burnedEver;

    private double[][] currentTemperature;
    private double[][] nextTemperature;
    private double[][] currentFuel;
    private double[][] nextFuel;
    private boolean[][] currentBurning;
    private boolean[][] nextBurning;
    private final double[][] peakTemperature;
    private String sourceDescription;

    public FireMap(int rows, int columns, long seed, Mode mode) {
        this(rows, columns, seed, mode, Landscape.MIXED,
                null, null, null);
    }

    public FireMap(int rows,
                   int columns,
                   long seed,
                   Mode mode,
                   Landscape landscape,
                   Integer ignitionTopRow,
                   Integer ignitionLeftColumn,
                   Integer ignitionPatchSize) {
        if (rows < 20 || columns < 20) {
            throw new IllegalArgumentException(
                    "The prototype requires at least 20 rows and 20 columns.");
        }

        this.rows = rows;
        this.columns = columns;
        this.terrain = new TerrainType[rows][columns];
        this.fixedHeatSource = new boolean[rows][columns];
        this.burnedEver = new boolean[rows][columns];

        this.currentTemperature = new double[rows][columns];
        this.nextTemperature = new double[rows][columns];
        this.currentFuel = new double[rows][columns];
        this.nextFuel = new double[rows][columns];
        this.currentBurning = new boolean[rows][columns];
        this.nextBurning = new boolean[rows][columns];
        this.peakTemperature = new double[rows][columns];

        if (landscape == Landscape.GRASS) {
            generateGrassLandscape();
        } else {
            generateLandscape(seed);
        }
        initialiseDynamicState();
        configureSources(mode, landscape, ignitionTopRow,
                ignitionLeftColumn, ignitionPatchSize);
    }

    /**
     * Advances the entire grid by one synchronous serial timestep.
     */
    public final StepResult step(Mode mode) {
        prepareNextState();
        StepResult result = updateRegion(
                mode, 1, rows - 1, 1, columns - 1);
        completeStep();
        return result;
    }

    /**
     * Prepares the write buffers for a new timestep. This must be called once
     * before any region of the grid is updated.
     */
    protected final void prepareNextState() {
        setNextBorders();
    }

    /**
     * Performs the serial stencil calculation for one rectangular region.
     * Bounds are half-open: [rowStart,rowEnd) x [columnStart,columnEnd).
     *
     * Different callers may execute this method concurrently only when their
     * regions do not overlap. Each region reads the shared current-state
     * arrays and writes only its own cells in the next-state arrays.
     */
    protected final StepResult updateRegion(
            Mode mode,
            int rowStart,
            int rowEnd,
            int columnStart,
            int columnEnd) {
        double maximumChange = 0.0;
        int burningCells = 0;
        int newlyIgnitedCells = 0;

        for (int row = rowStart; row < rowEnd; row++) {
            for (int column = columnStart;
                    column < columnEnd; column++) {
                TerrainType type = terrain[row][column];
                double current = currentTemperature[row][column];

                double neighbourAverage =
                        (currentTemperature[row - 1][column]
                         + currentTemperature[row + 1][column]
                         + currentTemperature[row][column - 1]
                         + currentTemperature[row][column + 1]) / 4.0;

                double diffusion = type.getDiffusionRate()
                        * (neighbourAverage - current);
                double cooling = type.getCoolingRate()
                        * (current - AMBIENT_TEMPERATURE);

                double generatedHeat = 0.0;
                if (mode == Mode.WILDFIRE
                        && currentBurning[row][column]) {
                    generatedHeat = type.getHeatOutput();
                }

                double updatedTemperature = clampTemperature(
                        current + diffusion - cooling + generatedHeat);

                if (mode == Mode.DIFFUSION
                        && fixedHeatSource[row][column]) {
                    updatedTemperature = FIXED_SOURCE_TEMPERATURE;
                }

                nextTemperature[row][column] = updatedTemperature;
                peakTemperature[row][column] = Math.max(
                        peakTemperature[row][column], updatedTemperature);
                maximumChange = Math.max(
                        maximumChange,
                        Math.abs(updatedTemperature - current));

                if (mode == Mode.WILDFIRE) {
                    double updatedFuel = currentFuel[row][column];
                    boolean willBurn = false;

                    if (currentBurning[row][column]) {
                        updatedFuel = Math.max(
                                0.0,
                                currentFuel[row][column]
                                        - type.getBurnRate());
                        willBurn = updatedFuel > FUEL_EPSILON;
                        burnedEver[row][column] = true;
                    } else if (updatedFuel > FUEL_EPSILON
                            && updatedTemperature
                                    >= type.getIgnitionTemperature()) {
                        willBurn = true;
                        newlyIgnitedCells++;
                    }

                    nextFuel[row][column] = updatedFuel;
                    nextBurning[row][column] = willBurn;
                    if (willBurn) {
                        burningCells++;
                    }
                } else {
                    nextFuel[row][column] = currentFuel[row][column];
                    nextBurning[row][column] = false;
                }
            }
        }

        return new StepResult(
                maximumChange, burningCells, newlyIgnitedCells);
    }

    /**
     * Makes the completed next-state buffers current. This must be called once
     * after all grid regions have finished.
     */
    protected final void completeStep() {
        swapStateBuffers();
    }

    private void generateGrassLandscape() {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                terrain[row][column] = TerrainType.GRASS;
            }
        }
        setBoundaryTerrain();
    }

    private void generateLandscape(long seed) {
        Random random = new Random(seed);

        /*
         * Build the vegetation pattern from two scales of smoothly
         * interpolated random noise. This creates irregular, connected
         * patches without the obvious stripes produced by trigonometric
         * functions.
         */
        double[][] broadNoise = createNoiseGrid(
                random,
                Math.max(4, rows / 90 + 3),
                Math.max(4, columns / 90 + 3));
        double[][] detailNoise = createNoiseGrid(
                random,
                Math.max(5, rows / 35 + 3),
                Math.max(5, columns / 35 + 3));

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double broad = sampleNoise(broadNoise, row, column);
                double detail = sampleNoise(detailNoise, row, column);
                double localVariation = random.nextDouble() * 2.0 - 1.0;

                double signal = 0.78 * broad
                        + 0.32 * detail
                        + 0.08 * localVariation;

                if (signal > 0.18) {
                    terrain[row][column] = TerrainType.FOREST;
                } else if (signal < -0.58) {
                    terrain[row][column] = TerrainType.ROCK;
                } else {
                    terrain[row][column] = TerrainType.GRASS;
                }
            }
        }

        /*
         * Rock is placed first and treated as a fixed obstacle. The river is
         * then routed around rock outcrops rather than painting over them.
         * The firebreak is added last; it pauses wherever it meets rock or
         * water and resumes on the far side.
         */
        addIrregularRockOutcrops(seed ^ 0x5DEECE66DL);
        addIrregularRiver(seed ^ 0xBB67AE8584CAA73BL);
        addMeanderingFirebreak(seed ^ 0x6A09E667F3BCC909L);
        setBoundaryTerrain();
    }

    private double[][] createNoiseGrid(
            Random random, int noiseRows, int noiseColumns) {
        double[][] noise = new double[noiseRows][noiseColumns];
        for (int row = 0; row < noiseRows; row++) {
            for (int column = 0; column < noiseColumns; column++) {
                noise[row][column] = random.nextDouble() * 2.0 - 1.0;
            }
        }
        return noise;
    }

    private double sampleNoise(
            double[][] noise, int row, int column) {
        double scaledRow = row * (noise.length - 1.0)
                / Math.max(1.0, rows - 1.0);
        double scaledColumn = column * (noise[0].length - 1.0)
                / Math.max(1.0, columns - 1.0);

        int row0 = (int) Math.floor(scaledRow);
        int column0 = (int) Math.floor(scaledColumn);
        int row1 = Math.min(row0 + 1, noise.length - 1);
        int column1 = Math.min(column0 + 1, noise[0].length - 1);

        double rowFraction = smoothStep(scaledRow - row0);
        double columnFraction = smoothStep(scaledColumn - column0);

        double top = linearInterpolate(
                noise[row0][column0],
                noise[row0][column1],
                columnFraction);
        double bottom = linearInterpolate(
                noise[row1][column0],
                noise[row1][column1],
                columnFraction);
        return linearInterpolate(top, bottom, rowFraction);
    }

    private double smoothStep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private double linearInterpolate(
            double first, double second, double fraction) {
        return first + fraction * (second - first);
    }

    private void addIrregularRiver(long seed) {
        Random random = new Random(seed);
        double centre = columns * (0.38 + 0.20 * random.nextDouble());
        double velocity = (random.nextDouble() - 0.5) * columns * 0.0015;
        double baseHalfWidth = Math.max(2.0, columns * 0.010);
        double halfWidth = baseHalfWidth;

        double[] preferredCentre = new double[rows];
        int[] leftHalfWidth = new int[rows];
        int[] rightHalfWidth = new int[rows];

        /*
         * First generate the irregular course the river would prefer to
         * follow if there were no obstacles.
         */
        for (int row = 0; row < rows; row++) {
            velocity = 0.94 * velocity
                    + (random.nextDouble() - 0.5) * columns * 0.0012;
            double maximumVelocity = columns * 0.0035;
            velocity = Math.max(-maximumVelocity,
                    Math.min(maximumVelocity, velocity));
            centre += velocity;

            double minimumCentre = columns * 0.08;
            double maximumCentre = columns * 0.92;
            if (centre < minimumCentre) {
                centre = minimumCentre;
                velocity = Math.abs(velocity);
            } else if (centre > maximumCentre) {
                centre = maximumCentre;
                velocity = -Math.abs(velocity);
            }

            double targetWidth = baseHalfWidth
                    * (0.65 + 0.75 * random.nextDouble());
            halfWidth = 0.92 * halfWidth + 0.08 * targetWidth;

            preferredCentre[row] = centre;
            leftHalfWidth[row] = Math.max(
                    1,
                    (int) Math.ceil(halfWidth)
                    + random.nextInt(3) - 1);
            rightHalfWidth[row] = Math.max(
                    1,
                    (int) Math.ceil(halfWidth)
                    + random.nextInt(3) - 1);
        }

        int[] routedCentre = routeRiverAroundRocks(
                preferredCentre, leftHalfWidth, rightHalfWidth);

        for (int row = 0; row < rows; row++) {
            int first = Math.max(
                    1, routedCentre[row] - leftHalfWidth[row]);
            int last = Math.min(
                    columns - 2,
                    routedCentre[row] + rightHalfWidth[row]);

            for (int column = first; column <= last; column++) {
                /* Defensive check: the routing algorithm should already
                 * have selected a completely rock-free corridor. */
                if (terrain[row][column] != TerrainType.ROCK) {
                    terrain[row][column] = TerrainType.WATER;
                }
            }
        }
    }

    /**
     * Finds a smooth top-to-bottom river route that stays close to the
     * preferred meandering course but never crosses a rock cell. This is a
     * small dynamic-programming path search performed only while generating
     * the landscape; it is not part of the timed simulation.
     */
    private int[] routeRiverAroundRocks(
            double[] preferredCentre,
            int[] leftHalfWidth,
            int[] rightHalfWidth) {
        final double infinity = Double.POSITIVE_INFINITY;
        final int maximumLateralStep = Math.max(6, columns / 50);
        final double movementWeight = 1.0;
        final double preferenceWeight = 0.035;

        int[][] predecessor = new int[rows][columns];
        double[] previousCost = new double[columns];
        double[] currentCost = new double[columns];
        Arrays.fill(previousCost, infinity);

        for (int row = 0; row < rows; row++) {
            Arrays.fill(currentCost, infinity);
            Arrays.fill(predecessor[row], -1);

            int[] rockPrefix = new int[columns + 1];
            for (int column = 0; column < columns; column++) {
                rockPrefix[column + 1] = rockPrefix[column]
                        + (terrain[row][column] == TerrainType.ROCK ? 1 : 0);
            }

            int firstCentre = 1 + leftHalfWidth[row];
            int lastCentre = columns - 2 - rightHalfWidth[row];

            for (int column = firstCentre;
                    column <= lastCentre;
                    column++) {
                int first = column - leftHalfWidth[row];
                int last = column + rightHalfWidth[row];
                int rockCells = rockPrefix[last + 1] - rockPrefix[first];
                if (rockCells != 0) {
                    continue;
                }

                double difference = column - preferredCentre[row];
                double localCost = preferenceWeight
                        * difference * difference;

                if (row == 0) {
                    currentCost[column] = localCost;
                    continue;
                }

                int firstPrevious = Math.max(
                        0, column - maximumLateralStep);
                int lastPrevious = Math.min(
                        columns - 1,
                        column + maximumLateralStep);

                double bestCost = infinity;
                int bestPrevious = -1;
                for (int previous = firstPrevious;
                        previous <= lastPrevious;
                        previous++) {
                    if (!Double.isFinite(previousCost[previous])) {
                        continue;
                    }
                    double movement = column - previous;
                    double candidateCost = previousCost[previous]
                            + movementWeight * movement * movement
                            + localCost;
                    if (candidateCost < bestCost) {
                        bestCost = candidateCost;
                        bestPrevious = previous;
                    }
                }

                if (bestPrevious >= 0) {
                    currentCost[column] = bestCost;
                    predecessor[row][column] = bestPrevious;
                }
            }

            double[] swap = previousCost;
            previousCost = currentCost;
            currentCost = swap;
        }

        int finalCentre = -1;
        double finalCost = infinity;
        for (int column = 0; column < columns; column++) {
            if (previousCost[column] < finalCost) {
                finalCost = previousCost[column];
                finalCentre = column;
            }
        }

        if (finalCentre < 0) {
            throw new IllegalStateException(
                    "Unable to route the river around the rock outcrops.");
        }

        int[] route = new int[rows];
        route[rows - 1] = finalCentre;
        for (int row = rows - 1; row > 0; row--) {
            int previous = predecessor[row][route[row]];
            if (previous < 0) {
                throw new IllegalStateException(
                        "River routing produced an incomplete path.");
            }
            route[row - 1] = previous;
        }
        return route;
    }

    private void addIrregularRockOutcrops(long seed) {
        Random random = new Random(seed);
        int clusters = Math.max(2, Math.min(5, (rows + columns) / 500 + 2));

        for (int cluster = 0; cluster < clusters; cluster++) {
            double centreRow = rows * (0.12 + 0.76 * random.nextDouble());
            double centreColumn = columns * (0.10 + 0.80 * random.nextDouble());
            double clusterRadius = Math.max(
                    4.0,
                    Math.min(rows, columns)
                    * (0.035 + 0.045 * random.nextDouble()));
            int lobes = 7 + random.nextInt(8);

            for (int lobe = 0; lobe < lobes; lobe++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = clusterRadius
                        * random.nextDouble() * 0.75;
                int lobeRow = (int) Math.round(
                        centreRow + Math.sin(angle) * distance);
                int lobeColumn = (int) Math.round(
                        centreColumn + Math.cos(angle) * distance);
                int radius = Math.max(
                        2,
                        (int) Math.round(clusterRadius
                                * (0.28 + 0.35 * random.nextDouble())));
                paintTerrainDisk(
                        lobeRow, lobeColumn, radius, TerrainType.ROCK);
            }
        }
    }

    private void addMeanderingFirebreak(long seed) {
        Random random = new Random(seed);
        double centre = columns * (0.68 + 0.14 * random.nextDouble());
        double velocity = (random.nextDouble() - 0.5) * columns * 0.0008;

        for (int row = 0; row < rows; row++) {
            velocity = 0.96 * velocity
                    + (random.nextDouble() - 0.5) * columns * 0.00055;
            centre += velocity;
            centre = Math.max(columns * 0.58,
                    Math.min(columns * 0.90, centre));

            int width = 1 + random.nextInt(3);
            int centreColumn = (int) Math.round(centre);
            for (int offset = -width; offset <= width; offset++) {
                int column = centreColumn + offset;
                if (column > 0 && column < columns - 1
                        && terrain[row][column] != TerrainType.ROCK
                        && terrain[row][column] != TerrainType.WATER) {
                    /* A firebreak is interrupted by a natural obstacle and
                     * resumes on the other side; it never cuts through rock
                     * or across the river. */
                    terrain[row][column] = TerrainType.BARE;
                }
            }
        }
    }

    private void paintTerrainDisk(
            int centreRow,
            int centreColumn,
            int radius,
            TerrainType type) {
        int firstRow = Math.max(1, centreRow - radius);
        int lastRow = Math.min(rows - 2, centreRow + radius);
        int firstColumn = Math.max(1, centreColumn - radius);
        int lastColumn = Math.min(columns - 2, centreColumn + radius);
        int radiusSquared = radius * radius;

        for (int row = firstRow; row <= lastRow; row++) {
            int rowOffset = row - centreRow;
            for (int column = firstColumn;
                    column <= lastColumn;
                    column++) {
                int columnOffset = column - centreColumn;
                if (rowOffset * rowOffset
                        + columnOffset * columnOffset <= radiusSquared) {
                    terrain[row][column] = type;
                }
            }
        }
    }

    private void setBoundaryTerrain() {
        for (int column = 0; column < columns; column++) {
            terrain[0][column] = TerrainType.BARE;
            terrain[rows - 1][column] = TerrainType.BARE;
        }
        for (int row = 0; row < rows; row++) {
            terrain[row][0] = TerrainType.BARE;
            terrain[row][columns - 1] = TerrainType.BARE;
        }
    }

    private void initialiseDynamicState() {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                currentTemperature[row][column] = AMBIENT_TEMPERATURE;
                nextTemperature[row][column] = AMBIENT_TEMPERATURE;
                peakTemperature[row][column] = AMBIENT_TEMPERATURE;

                double fuel = terrain[row][column].getInitialFuel();
                currentFuel[row][column] = fuel;
                nextFuel[row][column] = fuel;
            }
        }
    }

    private void configureSources(Mode mode,
                                  Landscape landscape,
                                  Integer ignitionTopRow,
                                  Integer ignitionLeftColumn,
                                  Integer ignitionPatchSize) {
        boolean customPatch = ignitionTopRow != null;

        if (customPatch) {
            validatePatch(ignitionTopRow, ignitionLeftColumn,
                    ignitionPatchSize);
            int sourceCells;
            if (mode == Mode.WILDFIRE) {
                sourceCells = ignitePatch(ignitionTopRow, ignitionLeftColumn,
                        ignitionPatchSize);
            } else {
                sourceCells = addFixedHeatSourcePatch(ignitionTopRow,
                        ignitionLeftColumn, ignitionPatchSize);
            }
            sourceDescription = String.format(
                    "square patch top-left=(%d,%d), size=%d, active cells=%d",
                    ignitionTopRow, ignitionLeftColumn,
                    ignitionPatchSize, sourceCells);
            return;
        }

        if (landscape == Landscape.GRASS) {
            int patchSize = 9;
            int topRow = defaultPatchCoordinate(rows, patchSize);
            int leftColumn = defaultPatchCoordinate(columns, patchSize);
            int sourceCells;
            if (mode == Mode.WILDFIRE) {
                sourceCells = ignitePatch(topRow, leftColumn, patchSize);
            } else {
                sourceCells = addFixedHeatSourcePatch(
                        topRow, leftColumn, patchSize);
            }
            sourceDescription = String.format(
                    "square patch top-left=(%d,%d), size=%d, active cells=%d",
                    topRow, leftColumn, patchSize, sourceCells);
            return;
        }

        int patchSize = 9;
        int preferredTopRow = centredPatchStart(
                rows / 2, rows, patchSize);
        int preferredLeftColumn = centredPatchStart(
                columns / 4, columns, patchSize);
        int[] patchStart = findBestBurnablePatch(
                preferredTopRow, preferredLeftColumn, patchSize);
        int topRow = patchStart[0];
        int leftColumn = patchStart[1];

        int sourceCells;
        if (mode == Mode.WILDFIRE) {
            sourceCells = ignitePatch(topRow, leftColumn, patchSize);
        } else {
            sourceCells = addFixedHeatSourcePatch(
                    topRow, leftColumn, patchSize);
        }
        sourceDescription = String.format(
                "square patch top-left=(%d,%d), size=%d, active cells=%d",
                topRow, leftColumn, patchSize, sourceCells);
    }

    private int defaultPatchCoordinate(int extent, int patchSize) {
        return Math.max(1, Math.min(20, extent - patchSize - 1));
    }

    private int[] findBestBurnablePatch(
            int preferredTopRow,
            int preferredLeftColumn,
            int patchSize) {
        int searchRadius = Math.max(
                patchSize * 3,
                Math.min(rows, columns) / 7);
        int firstRow = Math.max(1, preferredTopRow - searchRadius);
        int lastRow = Math.min(
                rows - patchSize - 1,
                preferredTopRow + searchRadius);
        int firstColumn = Math.max(
                1, preferredLeftColumn - searchRadius);
        int lastColumn = Math.min(
                columns - patchSize - 1,
                preferredLeftColumn + searchRadius);
        int step = Math.max(1, patchSize / 3);

        int bestRow = preferredTopRow;
        int bestColumn = preferredLeftColumn;
        int bestScore = -1;
        long bestDistanceSquared = Long.MAX_VALUE;

        for (int row = firstRow; row <= lastRow; row += step) {
            for (int column = firstColumn;
                    column <= lastColumn;
                    column += step) {
                int score = countBurnableCellsInPatch(
                        row, column, patchSize);
                long rowDistance = row - preferredTopRow;
                long columnDistance = column - preferredLeftColumn;
                long distanceSquared = rowDistance * rowDistance
                        + columnDistance * columnDistance;

                if (score > bestScore
                        || (score == bestScore
                        && distanceSquared < bestDistanceSquared)) {
                    bestScore = score;
                    bestDistanceSquared = distanceSquared;
                    bestRow = row;
                    bestColumn = column;
                }
            }
        }
        return new int[] {bestRow, bestColumn};
    }

    private int countBurnableCellsInPatch(
            int topRow, int leftColumn, int patchSize) {
        int count = 0;
        for (int row = topRow; row < topRow + patchSize; row++) {
            for (int column = leftColumn;
                    column < leftColumn + patchSize;
                    column++) {
                if (terrain[row][column].canBurn()) {
                    count++;
                }
            }
        }
        return count;
    }

    private int centredPatchStart(int centre, int extent, int patchSize) {
        int start = centre - patchSize / 2;
        return Math.max(1, Math.min(start, extent - patchSize - 1));
    }

    private void validatePatch(int topRow,
                               int leftColumn,
                               int patchSize) {
        if (topRow < 1 || leftColumn < 1
                || topRow + patchSize > rows - 1
                || leftColumn + patchSize > columns - 1) {
            throw new IllegalArgumentException(
                    "The ignition patch must lie entirely inside the "
                    + "non-boundary cells of the grid.");
        }
    }

    private int ignitePatch(int topRow,
                            int leftColumn,
                            int patchSize) {
        int ignited = 0;
        for (int row = topRow; row < topRow + patchSize; row++) {
            for (int column = leftColumn;
                    column < leftColumn + patchSize; column++) {
                if (terrain[row][column].canBurn()) {
                    currentBurning[row][column] = true;
                    currentTemperature[row][column] =
                            INITIAL_FIRE_TEMPERATURE;
                    peakTemperature[row][column] =
                            INITIAL_FIRE_TEMPERATURE;
                    ignited++;
                }
            }
        }
        if (ignited == 0) {
            throw new IllegalArgumentException(
                    "The selected ignition patch contains no burnable cells.");
        }
        return ignited;
    }

    private int addFixedHeatSourcePatch(int topRow,
                                        int leftColumn,
                                        int patchSize) {
        int sourceCells = 0;
        for (int row = topRow; row < topRow + patchSize; row++) {
            for (int column = leftColumn;
                    column < leftColumn + patchSize; column++) {
                if (terrain[row][column] != TerrainType.WATER) {
                    fixedHeatSource[row][column] = true;
                    currentTemperature[row][column] =
                            FIXED_SOURCE_TEMPERATURE;
                    peakTemperature[row][column] =
                            FIXED_SOURCE_TEMPERATURE;
                    sourceCells++;
                }
            }
        }
        if (sourceCells == 0) {
            throw new IllegalArgumentException(
                    "The selected source patch contains only water cells.");
        }
        return sourceCells;
    }

    private void igniteNearestBurnable(int targetRow, int targetColumn) {
        int[] location = findNearestBurnable(targetRow, targetColumn);
        int row = location[0];
        int column = location[1];
        currentBurning[row][column] = true;
        currentTemperature[row][column] = INITIAL_FIRE_TEMPERATURE;
        peakTemperature[row][column] = INITIAL_FIRE_TEMPERATURE;
    }

    private void addFixedHeatSource(int targetRow, int targetColumn) {
        int[] location = findNearestNonWater(targetRow, targetColumn);
        int row = location[0];
        int column = location[1];
        fixedHeatSource[row][column] = true;
        currentTemperature[row][column] = FIXED_SOURCE_TEMPERATURE;
        peakTemperature[row][column] = FIXED_SOURCE_TEMPERATURE;
    }

    private int[] findNearestBurnable(int targetRow, int targetColumn) {
        for (int radius = 0; radius < Math.max(rows, columns); radius++) {
            for (int rowOffset = -radius; rowOffset <= radius; rowOffset++) {
                for (int columnOffset = -radius;
                        columnOffset <= radius;
                        columnOffset++) {
                    int row = targetRow + rowOffset;
                    int column = targetColumn + columnOffset;
                    if (isInterior(row, column)
                            && terrain[row][column].canBurn()) {
                        return new int[] {row, column};
                    }
                }
            }
        }
        throw new IllegalStateException("No burnable cell exists in the landscape.");
    }

    private int[] findNearestNonWater(int targetRow, int targetColumn) {
        for (int radius = 0; radius < Math.max(rows, columns); radius++) {
            for (int rowOffset = -radius; rowOffset <= radius; rowOffset++) {
                for (int columnOffset = -radius;
                        columnOffset <= radius;
                        columnOffset++) {
                    int row = targetRow + rowOffset;
                    int column = targetColumn + columnOffset;
                    if (isInterior(row, column)
                            && terrain[row][column] != TerrainType.WATER) {
                        return new int[] {row, column};
                    }
                }
            }
        }
        throw new IllegalStateException("No suitable heat-source cell exists.");
    }

    private boolean isInterior(int row, int column) {
        return row > 0 && row < rows - 1
                && column > 0 && column < columns - 1;
    }

    private void setNextBorders() {
        for (int column = 0; column < columns; column++) {
            setNextBoundaryCell(0, column);
            setNextBoundaryCell(rows - 1, column);
        }
        for (int row = 1; row < rows - 1; row++) {
            setNextBoundaryCell(row, 0);
            setNextBoundaryCell(row, columns - 1);
        }
    }

    private void setNextBoundaryCell(int row, int column) {
        nextTemperature[row][column] = AMBIENT_TEMPERATURE;
        nextFuel[row][column] = currentFuel[row][column];
        nextBurning[row][column] = false;
        peakTemperature[row][column] = Math.max(
                peakTemperature[row][column], AMBIENT_TEMPERATURE);
    }

    private double clampTemperature(double value) {
        return Math.max(
                AMBIENT_TEMPERATURE,
                Math.min(MAXIMUM_TEMPERATURE, value));
    }

    private void swapStateBuffers() {
        double[][] temperatureSwap = currentTemperature;
        currentTemperature = nextTemperature;
        nextTemperature = temperatureSwap;

        double[][] fuelSwap = currentFuel;
        currentFuel = nextFuel;
        nextFuel = fuelSwap;

        boolean[][] burningSwap = currentBurning;
        currentBurning = nextBurning;
        nextBurning = burningSwap;
    }

    public int countBurnedCells() {
        int count = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (burnedEver[row][column]) {
                    count++;
                }
            }
        }
        return count;
    }

    public double getMaximumPeakTemperature() {
        double maximum = AMBIENT_TEMPERATURE;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                maximum = Math.max(maximum, peakTemperature[row][column]);
            }
        }
        return maximum;
    }

    public void writeImages(String outputPrefix) throws IOException {
        writeTerrainImage(outputPrefix + "_terrain.png");
        writePeakTemperatureImage(outputPrefix + "_peak.png");
        writeFinalStateImage(outputPrefix + "_final.png");
    }

    private void writeTerrainImage(String filename) throws IOException {
        BufferedImage image = new BufferedImage(
                columns, rows, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Color color = terrain[row][column].getDisplayColor();
                if (fixedHeatSource[row][column]) {
                    color = Color.WHITE;
                }
                image.setRGB(column, row, color.getRGB());
            }
        }
        writeImage(image, filename);
    }

    private void writePeakTemperatureImage(String filename) throws IOException {
        BufferedImage image = new BufferedImage(
                columns, rows, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                image.setRGB(
                        column,
                        row,
                        heatColorForTemperature(
                                peakTemperature[row][column]).getRGB());
            }
        }
        writeImage(image, filename);
    }

    private void writeFinalStateImage(String filename) throws IOException {
        BufferedImage image = new BufferedImage(
                columns, rows, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Color color;
                if (currentBurning[row][column]) {
                    color = Color.WHITE;
                } else if (burnedEver[row][column]
                        && terrain[row][column].canBurn()) {
                    color = new Color(38, 38, 38);
                } else {
                    color = terrain[row][column].getDisplayColor();
                    double heatFraction = Math.max(
                            0.0,
                            Math.min(1.0,
                                    (currentTemperature[row][column]
                                            - AMBIENT_TEMPERATURE) / 280.0));
                    if (heatFraction > 0.02) {
                        color = blend(
                                color,
                                heatColorForTemperature(
                                        currentTemperature[row][column]),
                                Math.min(0.85, heatFraction));
                    }
                }
                image.setRGB(column, row, color.getRGB());
            }
        }
        writeImage(image, filename);
    }

    private Color heatColorForTemperature(double temperature) {
        double value = Math.max(
                HEAT_TEMPERATURE_STOPS[0],
                Math.min(HEAT_TEMPERATURE_STOPS[
                        HEAT_TEMPERATURE_STOPS.length - 1], temperature));

        for (int i = 0; i < HEAT_TEMPERATURE_STOPS.length - 1; i++) {
            double lower = HEAT_TEMPERATURE_STOPS[i];
            double upper = HEAT_TEMPERATURE_STOPS[i + 1];
            if (value <= upper) {
                double fraction = (value - lower) / (upper - lower);
                return interpolate(
                        HEAT_COLORS[i], HEAT_COLORS[i + 1], fraction);
            }
        }
        return HEAT_COLORS[HEAT_COLORS.length - 1];
    }

    private Color interpolate(Color first, Color second, double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        int red = (int) Math.round(
                first.getRed() + f * (second.getRed() - first.getRed()));
        int green = (int) Math.round(
                first.getGreen() + f * (second.getGreen() - first.getGreen()));
        int blue = (int) Math.round(
                first.getBlue() + f * (second.getBlue() - first.getBlue()));
        return new Color(red, green, blue);
    }

    private Color blend(Color base, Color overlay, double fraction) {
        return interpolate(base, overlay, fraction);
    }

    private void writeImage(BufferedImage image, String filename)
            throws IOException {
        File output = new File(filename);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException(
                    "Could not create output directory: " + parent);
        }
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG image writer is available.");
        }
    }

    /**
     * Checks core state invariants. This method is intended for validation and
     * is deliberately not called inside the timed simulation loop.
     */
    public void validateState() {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double temperature = currentTemperature[row][column];
                double fuel = currentFuel[row][column];
                TerrainType type = terrain[row][column];

                if (!Double.isFinite(temperature)
                        || temperature < AMBIENT_TEMPERATURE - 1.0e-9
                        || temperature > MAXIMUM_TEMPERATURE + 1.0e-9) {
                    throw new IllegalStateException(
                            "Invalid temperature at (" + row + ","
                            + column + "): " + temperature);
                }
                if (!Double.isFinite(fuel) || fuel < -FUEL_EPSILON
                        || fuel > type.getInitialFuel() + FUEL_EPSILON) {
                    throw new IllegalStateException(
                            "Invalid fuel at (" + row + ","
                            + column + "): " + fuel);
                }
                if (!type.canBurn() && currentBurning[row][column]) {
                    throw new IllegalStateException(
                            "Non-burnable terrain is burning at (" + row
                            + "," + column + ").");
                }
                if (currentBurning[row][column] && fuel <= FUEL_EPSILON) {
                    throw new IllegalStateException(
                            "A cell is burning without fuel at (" + row
                            + "," + column + ").");
                }
            }
        }
    }

    public double getTemperature(int row, int column) {
        checkCoordinates(row, column);
        return currentTemperature[row][column];
    }

    public double getFuel(int row, int column) {
        checkCoordinates(row, column);
        return currentFuel[row][column];
    }

    public double getPeakTemperature(int row, int column) {
        checkCoordinates(row, column);
        return peakTemperature[row][column];
    }

    public boolean isBurning(int row, int column) {
        checkCoordinates(row, column);
        return currentBurning[row][column];
    }

    public boolean hasBurned(int row, int column) {
        checkCoordinates(row, column);
        return burnedEver[row][column];
    }

    public TerrainType getTerrainType(int row, int column) {
        checkCoordinates(row, column);
        return terrain[row][column];
    }

    private void checkCoordinates(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IndexOutOfBoundsException(
                    "Grid coordinate outside map: (" + row + ","
                    + column + ")");
        }
    }

    public String getSourceDescription() {
        return sourceDescription;
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return columns;
    }
}
