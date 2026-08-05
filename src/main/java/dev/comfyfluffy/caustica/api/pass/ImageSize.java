package dev.comfyfluffy.caustica.api.pass;

public sealed interface ImageSize permits ImageSize.DisplayRelative, ImageSize.Fixed {
    static ImageSize displayRelative(int divisor) {
        return new DisplayRelative(divisor);
    }

    static ImageSize fixed(int width, int height) {
        return new Fixed(width, height);
    }

    record DisplayRelative(int divisor) implements ImageSize {
        public DisplayRelative {
            if (divisor < 1) {
                throw new IllegalArgumentException("display-relative divisor must be positive");
            }
        }
    }

    record Fixed(int width, int height) implements ImageSize {
        public Fixed {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("fixed image size must be positive");
            }
        }
    }
}
