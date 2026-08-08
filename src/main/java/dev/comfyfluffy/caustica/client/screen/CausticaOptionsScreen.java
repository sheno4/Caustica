package dev.comfyfluffy.caustica.client.screen;

import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaGroupHeader;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaNavItem;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaPaint;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaRadio;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaScrollPane;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaSlider;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaTextButton;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaSummaryWidget;
import dev.comfyfluffy.caustica.client.screen.widget.CausticaToggle;
import dev.comfyfluffy.caustica.client.settings.CausticaSections;
import dev.comfyfluffy.caustica.client.settings.CompositionSummary;
import dev.comfyfluffy.caustica.client.settings.SettingControl;
import dev.comfyfluffy.caustica.client.settings.SettingGroup;
import dev.comfyfluffy.caustica.client.settings.SettingsCommit;
import dev.comfyfluffy.caustica.client.settings.SettingsSection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Caustica settings screen: a header, a sidebar of sections, a scrolling content pane, and a footer.
 *
 * <p>Every row comes from {@link CausticaSections}, which derives them from the registry and the two config
 * stores, so an installed extension gets a page without contributing any UI code.
 *
 * <p>Writes are in-memory as they happen — the renderer picks them up on the next frame — and reach disk
 * once, in {@link #removed()}.
 */
public final class CausticaOptionsScreen extends Screen {
    private final Screen parent;
    private final SettingsCommit commit;
    private final List<SettingsSection> sections;
    private final CausticaScrollPane content = new CausticaScrollPane();
    private int selectedSection;

    public CausticaOptionsScreen(Screen parent) {
        super(Component.translatable("caustica.screen.title"));
        this.parent = parent;
        this.commit = new SettingsCommit(CausticaApi.options());
        this.sections = CausticaSections.build(CausticaApi.registry(), CausticaApi.options());
    }

    private SettingsSection section() {
        return sections.get(selectedSection);
    }

    private int sidebarWidth() {
        return CausticaTheme.sidebarWidth(width);
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
            if (index == 2) {
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
        content.setWidth(width - sidebar);
        content.setHeight(contentBottom() - contentTop());
        addRenderableWidget(content);

        CausticaTextButton close = CausticaTextButton.close(font, section().accent(), this::onClose);
        close.setX(width - 24);
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
        done.setX(width - done.getWidth() - CausticaTheme.CONTENT_PAD);
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
        List<CausticaScrollPane.Entry> entries = new ArrayList<>();
        if (section().id().equals(CausticaSections.COMPOSITION_ID)) {
            entries.add(new CausticaScrollPane.Entry(new CausticaSummaryWidget(
                    CompositionSummary.of(CausticaApi.registry()), font, section().accent())));
        }
        boolean first = true;
        for (SettingGroup group : section().groups()) {
            CausticaGroupHeader header =
                    new CausticaGroupHeader(group, font, section().accent(), this::refreshVisibility);
            entries.add(new CausticaScrollPane.Entry(header, first ? 0 : CausticaTheme.GROUP_GAP));
            first = false;
            for (SettingControl row : group.rows()) {
                for (AbstractWidget widget : widgetsFor(row)) {
                    entries.add(new CausticaScrollPane.Entry(widget));
                }
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

    /**
     * The widgets one control contributes. A choice becomes one radio row per candidate rather than a cycle
     * button: with the candidates listed, which extension owns a slot and what else could is readable
     * without clicking through, and the default is visibly labelled as such.
     */
    private List<AbstractWidget> widgetsFor(SettingControl control) {
        return switch (control) {
            case SettingControl.BoolControl bool ->
                    List.of(new CausticaToggle(bool, font, section().accent(), this::refreshVisibility));
            case SettingControl.RangeControl range ->
                    List.of(new CausticaSlider(range, font, section().accent()));
            case SettingControl.ChoiceControl<?> choice -> radioRows(choice);
        };
    }

    private <T> List<AbstractWidget> radioRows(SettingControl.ChoiceControl<T> choice) {
        List<AbstractWidget> rows = new ArrayList<>();
        for (T candidate : choice.choices()) {
            rows.add(new CausticaRadio<>(choice, candidate, font, section().accent(), this::refreshVisibility));
        }
        return rows;
    }

    @Override
    protected void repositionElements() {
        rebuildWidgets();
    }

    /** No blur and no panorama: the screen is full-bleed, so nothing behind it is ever visible. */
    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
        graphics.fillGradient(0, 0, width, height, CausticaTheme.BACKDROP_TOP, CausticaTheme.BACKDROP_BOTTOM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int sidebar = sidebarWidth();
        CausticaPaint.panel(graphics, 0, 0, width, CausticaTheme.HEADER_HEIGHT, CausticaTheme.HEADER_BODY);
        CausticaPaint.panel(graphics, 0, contentTop(), sidebar, contentBottom() - contentTop(),
                CausticaTheme.SIDEBAR_BODY);
        graphics.fill(sidebar, contentTop(), width, contentBottom(), CausticaTheme.CONTENT_BODY);
        CausticaPaint.panel(graphics, 0, contentBottom(), width, CausticaTheme.FOOTER_HEIGHT,
                CausticaTheme.FOOTER_BODY);
        graphics.fill(sidebar, contentTop(), sidebar + 1, contentBottom(), CausticaTheme.DIVIDER);

        wordmark(graphics);
        graphics.text(font, Component.translatable("caustica.nav.engine"), 6,
                contentTop() + 4, CausticaTheme.TEXT_DISABLED, false);
        if (sections.size() > 2) {
            graphics.text(font, Component.translatable("caustica.nav.extensions"), 6,
                    contentTop() + CausticaTheme.NAV_HEADING_HEIGHT + 2 * CausticaTheme.NAV_ITEM_HEIGHT + 4,
                    CausticaTheme.TEXT_DISABLED, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
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
        commit.save();
    }
}
