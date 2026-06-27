#ifndef _BOARD_CONFIG_H_
#define _BOARD_CONFIG_H_

#include <driver/gpio.h>
#include <driver/spi_master.h>
#include <esp_io_expander.h>

#ifdef CONFIG_SD_CARD_MMC_INTERFACE
// Define to use 4-bit SDMMC bus width; comment out to use 1-bit bus width
// #define CARD_SDMMC_BUS_WIDTH_4BIT

#ifdef CARD_SDMMC_BUS_WIDTH_4BIT
#define CARD_SDMMC_CLK_GPIO GPIO_NUM_14 // CLK pin
#define CARD_SDMMC_CMD_GPIO GPIO_NUM_17 // MOSI pin or DI
#define CARD_SDMMC_D0_GPIO  GPIO_NUM_16 // MISO pin or DO
#define CARD_SDMMC_D1_GPIO  GPIO_NUM_NC
#define CARD_SDMMC_D2_GPIO  GPIO_NUM_NC
#define CARD_SDMMC_D3_GPIO  GPIO_NUM_NC // Extend_IO3
#else
#define CARD_SDMMC_CLK_GPIO GPIO_NUM_14
#define CARD_SDMMC_CMD_GPIO GPIO_NUM_17
#define CARD_SDMMC_D0_GPIO  GPIO_NUM_16
#define CARD_SDMMC_D3_GPIO  GPIO_NUM_NC // Extend_IO3
#endif
#endif // CONFIG_SD_CARD_MMC_INTERFACE

#ifdef CONFIG_SD_CARD_SPI_INTERFACE
#define CARD_SPI_MOSI_GPIO GPIO_NUM_17  // DI
#define CARD_SPI_MISO_GPIO GPIO_NUM_16  // DO
#define CARD_SPI_SCLK_GPIO GPIO_NUM_14  // CLK
#define CARD_SPI_CS_GPIO   3 // Extend_IO3
#endif // CONFIG_SD_CARD_SPI_INTERFACE

#define AUDIO_INPUT_SAMPLE_RATE  16000
#define AUDIO_OUTPUT_SAMPLE_RATE 24000

#define BOOT_BUTTON_GPIO        GPIO_NUM_0

#define AUDIO_I2S_MIC_GPIO_WS   GPIO_NUM_2
#define AUDIO_I2S_MIC_GPIO_SCK  GPIO_NUM_15
#define AUDIO_I2S_MIC_GPIO_DIN  GPIO_NUM_39
#define AUDIO_I2S_SPK_GPIO_DOUT GPIO_NUM_47
#define AUDIO_I2S_SPK_GPIO_BCLK GPIO_NUM_48
#define AUDIO_I2S_SPK_GPIO_LRCK GPIO_NUM_38

#define I2C_BUS_PORT            I2C_NUM_0
#define I2C_BUS_SCL_PIN         GPIO_NUM_10
#define I2C_BUS_SDA_PIN         GPIO_NUM_11      

// TCA9554 I2C IO Expander
#define IO_EXPANDER_I2C_TCA9554_ADDR ESP_IO_EXPANDER_I2C_TCA9554_ADDRESS_000
#define IO_EXPANDER_I2C_PORT    I2C_BUS_PORT
#define IO_EXPANDER_I2C_SCL_PIN I2C_BUS_SCL_PIN
#define IO_EXPANDER_I2C_SDA_PIN I2C_BUS_SDA_PIN
#define IO_EXPANDER_01_TP_RST   IO_EXPANDER_PIN_NUM_0 // EXIO1 for TouchPad Reset
#define IO_EXPANDER_02_LCD_RST  IO_EXPANDER_PIN_NUM_2 // EXIO2 for LCD Reset
#define IO_EXPANDER_03_SDMMC    IO_EXPANDER_PIN_NUM_3 // EXIO3 for SDMMC

#define DISPLAY_WIDTH           360
#define DISPLAY_HEIGHT          360
#define DISPLAY_MIRROR_X        false
#define DISPLAY_MIRROR_Y        false
#define DISPLAY_SWAP_XY         false

#define QSPI_LCD_H_RES          (360)
#define QSPI_LCD_V_RES          (360)
#define QSPI_LCD_BIT_PER_PIXEL  (16)

#define QSPI_LCD_HOST           SPI2_HOST
#define QSPI_PIN_NUM_LCD_PCLK   GPIO_NUM_40
#define QSPI_PIN_NUM_LCD_CS     GPIO_NUM_21
#define QSPI_PIN_NUM_LCD_DATA0  GPIO_NUM_46
#define QSPI_PIN_NUM_LCD_DATA1  GPIO_NUM_45
#define QSPI_PIN_NUM_LCD_DATA2  GPIO_NUM_42
#define QSPI_PIN_NUM_LCD_DATA3  GPIO_NUM_41
#define QSPI_PIN_NUM_LCD_RST    GPIO_NUM_NC // LCD reset, active low
#define QSPI_PIN_NUM_LCD_BL     GPIO_NUM_5

#define DISPLAY_OFFSET_X  0
#define DISPLAY_OFFSET_Y  0

// Touchscreen section (cst816s)
#define TOUCH_I2C_NUM           I2C_BUS_PORT
#define TOUCH_I2C_SCL_PIN       I2C_BUS_SCL_PIN
#define TOUCH_I2C_SDA_PIN       I2C_BUS_SDA_PIN
#define TOUCH_RST_PIN           GPIO_NUM_NC  // Touchscreen reset, active low
#define TOUCH_INT_PIN           GPIO_NUM_4   // Touch interrupt, input low level when touched
#define TOUCH_I2C_ADDR          0x15         // Default address of cst816s

#define DISPLAY_BACKLIGHT_PIN   QSPI_PIN_NUM_LCD_BL
#define DISPLAY_BACKLIGHT_OUTPUT_INVERT false

#define TAIJIPI_ST77916_PANEL_BUS_QSPI_CONFIG(sclk, d0, d1, d2, d3, max_trans_sz) \
    {                                                                             \
        .data0_io_num = d0,                                                       \
        .data1_io_num = d1,                                                       \
        .sclk_io_num = sclk,                                                      \
        .data2_io_num = d2,                                                       \
        .data3_io_num = d3,                                                       \
        .max_transfer_sz = max_trans_sz,                                          \
    }


#endif // _BOARD_CONFIG_H_
