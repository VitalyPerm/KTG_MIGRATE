package com.elvitalya.ktg_migrate

interface KtgCallback {
    /**
     * При получении данных от монитора
     *
     * @param newData новые данные
     */
    fun onMonitorDataReceived(newData: PSChartData?)

    /**
     * При изменениии статуса подключения
     *
     * @param status идентификатор статуса
     */
    fun onConnectionStatusChanged(status: Int)

    /**
     * При получении данных о мониторе
     *
     * @param serialNumber серийный номер
     * @param deviceModel  номер устройства
     */
    fun onCharacteristicsReceived(serialNumber: String?, deviceModel: String?)

    /**
     * При изменении состояния исследования
     *
     * @param state состояние
     */
    fun onStateChanged(state: EState?)
}