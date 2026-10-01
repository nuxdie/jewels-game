package com.local.jewels;

/** The twelve ports of a voyage: what the place looks like and what it trades in. */
final class Planets {
    static final int COUNT = 12;

    static final class P {
        final String name, goods, intro;
        final int scene, accent;
        final long seed;

        P(String name, String goods, int scene, int accent, long seed, String intro) {
            this.name = name; this.goods = goods; this.scene = scene; this.accent = accent; this.seed = seed; this.intro = intro;
        }
    }

    static P get(int i) {
        return ALL[((i % COUNT) + COUNT) % COUNT];
    }

    static final P[] ALL = {
            new P("Sabulon", "water-glass", Art.DUNES, 0xFFFFA040, 11,
                    "A sea of dunes under a bruised sky. The spice-wind never stops here, and water-glass is worth more than gold."),

            new P("Thessaly Reach", "singing crystal", Art.CRYSTAL, 0xFF8AF0FF, 22,
                    "A violet night over fields of humming crystal. The crystals sing when you hold them, and no two sing the same note."),

            new P("Mire of Orrum", "mire-pearls", Art.SWAMP, 0xFFA0D050, 33,
                    "Warm fog, black water, trees that creak like old doors. Mire-pearls grow in the mud between the stilt-huts, if you know where to dig."),

            new P("Vashti Bazaar", "starlight silk", Art.BAZAAR, 0xFFFF5A9A, 44,
                    "Lanterns, domes, and a thousand stalls under a magenta dusk. Every deal here is a performance, and the crowd always votes."),

            new P("Kolos Mesa", "red iron", Art.MESA, 0xFFFF6A3A, 55,
                    "Twin suns beat down on the red mesas. The iron road runs through here, and it respects only strength and nerve."),

            new P("Nimbus Tor", "cloud-amber", Art.CLOUDS, 0xFF9AD8FF, 66,
                    "Platforms float on the clouds of a gas giant. Amber condenses from the storm-light below, and the wind carries it up to the markets."),

            new P("Gardens of Myce", "dream-spores", Art.FUNGAL, 0xFF50FFD0, 77,
                    "A glowing night forest of giant mushrooms, very old and very slow. Some say the whole forest is one living thing, dreaming in spores."),

            new P("Ember Throne", "salamander coal", Art.VOLCANO, 0xFFFF5020, 88,
                    "Rivers of lava and a throne carved from cooled fire. Salamander coal is mined from its steps, still warm."),

            new P("Glacis", "aurora ice", Art.ICE, 0xFFCFE8FF, 99,
                    "An ice world lit by aurora that never sets. Fishers cut the frozen seas for aurora ice, and every block holds a little of the sky."),

            new P("Pelagia", "deep-sea glass", Art.OCEAN, 0xFF40E0D0, 111,
                    "An ocean world with no shore, only arches of black rock. Deals are struck on deck in the spray, and deep-sea glass washes up with every tide."),

            new P("The Orrery", "star charts", Art.STATION, 0xFFE0C070, 122,
                    "A station of brass rings turning around a dead star. Its star charts are the only way to the galaxy's core."),

            new P("Throne of Night", "the last jewel", Art.CORE, 0xFFFFD060, 133,
                    "At the galaxy's core, a black hole turns slowly. Every world you've visited has paid tribute here. One last game. One last deal.")
    };
}
