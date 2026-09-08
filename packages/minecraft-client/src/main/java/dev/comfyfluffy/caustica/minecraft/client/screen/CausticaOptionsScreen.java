package dev.comfyfluffy.caustica.minecraft.client.screen;

import dev.comfyfluffy.caustica.config.CausticaOptions;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaGroupHeader;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaDropdown;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaNavItem;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaPaint;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaScrollPane;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaSlider;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaTextButton;
import dev.comfyfluffy.caustica.minecraft.client.screen.widget.CausticaToggle;
import dev.comfyfluffy.caustica.minecraft.client.settings.CausticaSections;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingGroup;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingsSection;
import dev.comfyfluffy.caustica.settings.SettingsRegistry;
import dev.comfyfluffy.caustica.settings.Option;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Caustica settings screen: a header, a sidebar of sections, a scrolling content pane, and a footer.
 *
 * <p>Every row comes from {@link CausticaSections}, which derives them from the settings registry and shared
 * preference store, so an installed extension gets a page without contributing any UI code.
 *
 * <p>Writes are in-memory as they happen — the renderer picks them up on the next frame — and reach disk
 * once, in {@link #removed()}.
 */
public final class CausticaOptionsScreen extends Screen {
    private final Screen parent;
    private final CausticaOptions options;
    private final List<SettingsSection> sections;
    private final CausticaScrollPane content = new CausticaScrollPane();
    private int selectedSection;
    /** The one open dropdown, held here because its list is drawn and hit-tested outside the pane. */
    private CausticaDropdown<?> openDropdown;

    public CausticaOptionsScreen(Screen parent, SettingsRegistry registry, CausticaOptions options,
                                Predicate<Option<?>> available) {
        super(Component.translatable("caustica.screen.title"));
        this.parent = parent;
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.sections = CausticaSections.build(java.util.Objects.requireNonNull(registry, "registry"), options,
                java.util.Objects.requireNonNull(available, "available"));
    }

    private SettingsSection section() {
        return sections.get(selectedSection);
    }

    private int sidebarWidth() {
        return CausticaTheme.sidebarWidth(width);
    }

    /** The panel occupies this much of the left edge; everything right of it is left untouched. */
    private int panelWidth() {
        return CausticaTheme.panelWidth(width);
    }

    private int contentTop() {
        return CausticaTheme.HEADER_HEIGHT;
    }

    private int contentBottom() {
        return height - CausticaTheme.FOOTER_HEIGHT;
    }

    @Override
    protected void init() {
        int sidebar = sidebarWidth();
        int navY = contentTop() + CausticaTheme.NAV_HEADING_HEIGHT;
        for (int index = 0; index < sections.size(); index++) {
            int target = index;
            SettingsSection entry = sections.get(index);
            // A heading before the first feature page separates the engine's own pages from extensions'.
            if (index == 1) {
                navY += CausticaTheme.NAV_HEADING_HEIGHT;
            }
            CausticaNavItem item = new CausticaNavItem(entry, font, () -> selectedSection == target,
                    () -> selectSection(target));
            item.setX(0);
            item.setY(navY);
            item.setWidth(sidebar);
            addRenderableWidget(item);
            navY += CausticaTheme.NAV_ITEM_HEIGHT;
        }

        content.setX(sidebar);
        content.setY(contentTop());
        content.setWidth(panelWidth() - sidebar);
        content.setHeight(contentBottom() - contentTop());
        addRenderableWidget(content);

        CausticaTextButton close = CausticaTextButton.close(font, section().accent(), this::onClose);
        close.setX(panelWidth() - 24);
        close.setY(8);
        addRenderableWidget(close);

        CausticaTextButton reset = CausticaTextButton.text(
                Component.translatable("caustica.screen.reset_section"), font, section().accent(),
                this::resetSection);
        reset.setX(CausticaTheme.CONTENT_PAD);
        reset.setY(contentBottom() + 8);
        addRenderableWidget(reset);

        CausticaTextButton done = CausticaTextButton.text(
                Component.translatable("caustica.screen.done"), font, section().accent(), this::onClose);
        done.setX(panelWidth() - done.getWidth() - CausticaTheme.CONTENT_PAD);
        done.setY(contentBottom() + 8);
        addRenderableWidget(done);

        rebuildContent();
    }

    private void selectSection(int index) {
        selectedSection = index;
        rebuildContent();
    }

    private void resetSection() {
        section().reset();
        rebuildContent();
    }

    /** Rebuilds the content pane's rows for the current section. */
    private void rebuildContent() {
        closeDropdown();
        List<CausticaScrollPane.Entry> entries = new ArrayList<>();
        boolean first = true;
        for (SettingGroup group : section().groups()) {
            CausticaGroupHeader header =
                    new CausticaGroupHeader(group, font, section().accent(), this::refreshVisibility);
            entries.add(new CausticaScrollPane.Entry(header, first ? 0 : CausticaTheme.GROUP_GAP));
            first = false;
            for (SettingControl row : group.rows()) {
                entries.add(new CausticaScrollPane.Entry(widgetFor(row)));
            }
        }
        content.setEntries(entries);
        refreshVisibility();
    }

    /** Applies each group's collapse state to the rows that follow its header, then re-lays the stack. */
    private void refreshVisibility() {
        boolean visible = true;
        for (CausticaScrollPane.Entry entry : content.entries()) {
            if (entry.widget() instanceof CausticaGroupHeader header) {
                header.visible = true;
                visible = header.rowsVisible();
            } else {
                entry.widget().visible = visible;
            }
        }
        content.reflow();
    }

    private AbstractWidget widgetFor(SettingControl control) {
        return switch (control) {
            case SettingControl.BoolControl bool ->
                    new CausticaToggle(bool, font, section().accent(), this::refreshVisibility);
            case SettingControl.RangeControl range ->
                    new CausticaSlider(range, font, section().accent());
            case SettingControl.ChoiceControl<?> choice ->
                    new CausticaDropdown<>(choice, font, section().accent(), this::toggleDropdown);
        };
    }

    private void toggleDropdown(CausticaDropdown<?> dropdown) {
        boolean wasOpen = openDropdown == dropdown;
        closeDropdown();
        if (!wasOpen) {
            openDropdown = dropdown;
            dropdown.expand(height);
        }
    }

    private void closeDropdown() {
        if (openDropdown != null) {
            openDropdown.collapse();
            openDropdown = null;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (openDropdown != null) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                closeDropdown();
                return true;
            }
            if (openDropdown.keyPressedPopup(event)) return true;
            closeDropdown();
        }
        return super.keyPressed(event);
    }

    /**
     * An open list is drawn outside the content pane, so it has to be offered the click before the pane gets
     * it — otherwise the row underneath would take a click aimed at the list floating above it.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (openDropdown != null) {
            boolean insideRow = openDropdown.isMouseOver(event.x(), event.y());
            boolean insideList = openDropdown.clickPopup(event.x(), event.y());
            closeDropdown();
            if (insideList || insideRow) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The list is anchored to a row that scrolls, so it closes rather than detaching from it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        closeDropdown();
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void repositionElements() {
        rebuildWidgets();
    }

    /**
     * Suppresses vanilla backgrounds and blur so the world remains visible beside the settings panel.
     */
    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected void extractPanorama(GuiGraphicsExtractor graphics, float partialTick) {
    }

    @Override
    public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int sidebar = sidebarWidth();
        int panel = panelWidth();
        // Every fill stops at the panel edge. Nothing is drawn to the right of it at all, so the scene there
        // is exactly as the renderer left it.
        graphics.fillGradient(0, 0, panel, height, CausticaTheme.BACKDROP_TOP, CausticaTheme.BACKDROP_BOTTOM);
        CausticaPaint.panel(graphics, 0, 0, panel, CausticaTheme.HEADER_HEIGHT, CausticaTheme.HEADER_BODY);
        CausticaPaint.panel(graphics, 0, contentTop(), sidebar, contentBottom() - contentTop(),
                CausticaTheme.SIDEBAR_BODY);
        graphics.fill(sidebar, contentTop(), panel, contentBottom(), CausticaTheme.CONTENT_BODY);
        CausticaPaint.panel(graphics, 0, contentBottom(), panel, CausticaTheme.FOOTER_HEIGHT,
                CausticaTheme.FOOTER_BODY);
        graphics.fill(sidebar, contentTop(), sidebar + 1, contentBottom(), CausticaTheme.DIVIDER);
        graphics.fill(panel - 1, 0, panel, height, CausticaTheme.DIVIDER);

        wordmark(graphics);
        graphics.text(font, Component.translatable("caustica.nav.engine"), 6,
                contentTop() + 4, CausticaTheme.TEXT_DISABLED, false);
        if (sections.size() > 1) {
            graphics.text(font, Component.translatable("caustica.nav.extensions"), 6,
                    contentTop() + CausticaTheme.NAV_HEADING_HEIGHT + CausticaTheme.NAV_ITEM_HEIGHT + 4,
                    CausticaTheme.TEXT_DISABLED, false);
        }

        // Covered rows must not queue tooltips over the popup that owns the pointer.
        super.extractRenderState(graphics, openDropdown == null ? mouseX : -1,
                openDropdown == null ? mouseY : -1, partialTick);
        // After the pane, so an open list is not clipped by its scissor.
        if (openDropdown != null) {
            openDropdown.extractPopup(graphics, mouseX, mouseY);
        }
    }

    /** Drawn per character with extra advance — letterspacing is the cheapest cue that this is bespoke. */
    private void wordmark(GuiGraphicsExtractor graphics) {
        String text = getTitle().getString();
        int x = CausticaTheme.CONTENT_PAD;
        int y = CausticaPaint.textBaseline(font, 0, CausticaTheme.HEADER_HEIGHT);
        for (int index = 0; index < text.length(); index++) {
            String character = text.substring(index, index + 1);
            graphics.text(font, character, x, y, CausticaTheme.TEXT_PRIMARY, true);
            x += font.width(character) + 2;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        options.save();
    }
}
