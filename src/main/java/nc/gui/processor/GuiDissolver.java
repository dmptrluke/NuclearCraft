package nc.gui.processor;

import nc.container.processor.ContainerDissolver;
import nc.gui.GuiFluidRenderer;
import nc.gui.GuiItemRenderer;
import nc.gui.NCGuiButton;
import nc.init.NCItems;
import nc.network.EmptyTankButtonPacket;
import nc.network.GetFluidInTankPacket;
import nc.network.PacketHandler;
import nc.tile.processor.TileItemFluidProcessor;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fluids.FluidStack;

public class GuiDissolver extends GuiItemFluidProcessor {
	
	public static FluidStack fluid0, fluid1 = null;
	
	public GuiDissolver(EntityPlayer player, TileItemFluidProcessor tile) {
		super("dissolver", player, new ContainerDissolver(player, tile));
		this.tile = tile;
		xSize = 176;
		ySize = 166;
	}
	
	@Override
	public void renderTooltips(int mouseX, int mouseY) {
		drawFluidTooltip(fluid0, tile.getTanks().get(0), mouseX, mouseY, 66, 35, 16, 16);
		drawFluidTooltip(fluid1, tile.getTanks().get(1), mouseX, mouseY, 122, 31, 24, 24);
		
		drawEnergyTooltip(tile, mouseX, mouseY, 8, 6, 16, 74);
	}
	
	@Override
	protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
		super.drawGuiContainerForegroundLayer(mouseX, mouseY);
		
		GuiItemRenderer itemRenderer = new GuiItemRenderer(132, ySize - 102, 0.5F, NCItems.upgrade, 0);
		itemRenderer.draw();
	}
	
	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		super.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
		
		double e = Math.round(((double) tile.getEnergyStorage().getEnergyStored()) / ((double) tile.getEnergyStorage().getMaxEnergyStored()) * 74);
		if (tile.baseProcessPower != 0) drawTexturedModalRect(guiLeft + 8, guiTop + 6 + 74 - (int) e, 176, 90 + 74 - (int) e, 16, (int) e);
		
		int k = getCookProgressScaled(37);
		drawTexturedModalRect(guiLeft + 84, guiTop + 35, 176, 3, k, 18);
		
		if (tick == 0) sendTankInfo();
		
		GuiFluidRenderer.renderGuiTank(fluid0, tile.getTanks().get(0).getCapacity(), guiLeft + 66, guiTop + 35, zLevel, 16, 16);
		GuiFluidRenderer.renderGuiTank(fluid1, tile.getTanks().get(1).getCapacity(), guiLeft + 122, guiTop + 31, zLevel, 24, 24);
	}
	
	@Override
	public void initGui() {
		super.initGui();
		buttonList.add(new NCGuiButton.EmptyTankButton(0, guiLeft + 66, guiTop + 35, 16, 16));
		buttonList.add(new NCGuiButton.EmptyTankButton(1, guiLeft + 122, guiTop + 31, 24, 24));
	}
	
	@Override
	protected void actionPerformed(GuiButton guiButton) {
		if (tile.getWorld().isRemote) {
			for (int i = 0; i < 2; i++) if (guiButton.id == i && isShiftKeyDown()) {
				PacketHandler.instance.sendToServer(new EmptyTankButtonPacket(tile, i));
			}
		}
	}
	
	@Override
	protected void sendTankInfo() {
		PacketHandler.instance.sendToServer(new GetFluidInTankPacket(tile.getPos(), 0, "nc.gui.processor.GuiDissolver", "fluid0"));
		PacketHandler.instance.sendToServer(new GetFluidInTankPacket(tile.getPos(), 1, "nc.gui.processor.GuiDissolver", "fluid1"));
	}
}
