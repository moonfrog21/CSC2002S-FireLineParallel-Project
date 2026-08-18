import java.awt.Color;

/**
 * Fixed terrain properties used by the Fireline simulation.
 *
 * The values are chosen to create a deterministic, visually interesting
 * educational model. They are not intended to predict real wildfire behaviour.
 */
public enum TerrainType {
    /*
     * Parameters are ordered as:
     * diffusion, cooling, initial fuel, burn rate, ignition temperature,
     * heat generated per timestep. These values form the frozen 2026 model.
     */
    GRASS('G', new Color(132, 184, 90), 0.24, 0.012, 1.0, 0.025, 80.0, 12.0),
    FOREST('F', new Color(35, 94, 50), 0.18, 0.008, 1.8, 0.030, 105.0, 16.0),
    BARE('B', new Color(167, 128, 91), 0.20, 0.025, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0),
    ROCK('R', new Color(118, 118, 118), 0.10, 0.015, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0),
    WATER('W', new Color(58, 121, 184), 0.25, 0.180, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0);

    private final char symbol;
    private final Color displayColor;
    private final double diffusionRate;
    private final double coolingRate;
    private final double initialFuel;
    private final double burnRate;
    private final double ignitionTemperature;
    private final double heatOutput;

    TerrainType(char symbol,
                Color displayColor,
                double diffusionRate,
                double coolingRate,
                double initialFuel,
                double burnRate,
                double ignitionTemperature,
                double heatOutput) {
        this.symbol = symbol;
        this.displayColor = displayColor;
        this.diffusionRate = diffusionRate;
        this.coolingRate = coolingRate;
        this.initialFuel = initialFuel;
        this.burnRate = burnRate;
        this.ignitionTemperature = ignitionTemperature;
        this.heatOutput = heatOutput;
    }

    public char getSymbol() {
        return symbol;
    }

    public Color getDisplayColor() {
        return displayColor;
    }

    public double getDiffusionRate() {
        return diffusionRate;
    }

    public double getCoolingRate() {
        return coolingRate;
    }

    public double getInitialFuel() {
        return initialFuel;
    }

    public double getBurnRate() {
        return burnRate;
    }

    public double getIgnitionTemperature() {
        return ignitionTemperature;
    }

    public double getHeatOutput() {
        return heatOutput;
    }

    public boolean canBurn() {
        return initialFuel > 0.0;
    }
}
