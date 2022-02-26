package dev.momostudios.coldsweat.client.gui;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import dev.momostudios.coldsweat.ColdSweat;
import dev.momostudios.coldsweat.common.container.BoilerContainer;
import net.minecraft.world.entity.player.Inventory;

public class BoilerScreen extends ContainerScreen<BoilerContainer>
{
    private static final ResourceLocation BOILER_GUI = new ResourceLocation(ColdSweat.MOD_ID, "textures/gui/screen/boiler_gui.png");
    private static final ResourceLocation FUEL_GAUGE = new ResourceLocation(ColdSweat.MOD_ID, "textures/gui/screen/lava_gauge.png");
    Component name = new TranslatableComponent("block." + ColdSweat.MOD_ID + ".boiler");
    int titleX = this.xSize / 2 - name.toString().length() / 18;
    int fuelLevel;

    public BoilerScreen(BoilerContainer screenContainer, Inventory inv, Component titleIn)
    {
        super(screenContainer, inv, titleIn);
        this.leftPos = 0;
        this.topPos = 0;
        this.width = 175;
        this.height = 201;
        this.fuelLevel = screenContainer.te..getInt("fuel");
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks)
    {
        this.renderBackground(matrixStack);
        super.render(matrixStack, mouseX, mouseY, partialTicks);
        this.renderHoveredTooltip(matrixStack, mouseX, mouseY);
    }
    @Override
    protected void drawGuiContainerForegroundLayer(MatrixStack matrixStack, int x, int y)
    {
        this.font.drawText(matrixStack, this.playerInventory.getDisplayName(), (float) this.playerInventoryTitleX, (float) this.playerInventoryTitleY + 6, 4210752);
        this.font.drawText(matrixStack, name, 88 - font.getStringWidth(name.getString()) / 2f, 9f, 4210752);

        this.minecraft.textureManager.bindTexture(FUEL_GAUGE);
        this.blit(matrixStack, 108, 62, 0, 0, (int) (this.container.getFuel() / 31.25), 16, 32, 16);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void drawGuiContainerBackgroundLayer(MatrixStack matrixStack, float partialTicks, int mouseX, int mouseY)
    {
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        this.minecraft.textureManager.bindTexture(BOILER_GUI);
        int x = (this.width - this.xSize) / 2;
        int y = (this.height - this.ySize) / 2;
        this.blit(matrixStack, x ,y, 0, 0, this.xSize, this.ySize);
    }
}
