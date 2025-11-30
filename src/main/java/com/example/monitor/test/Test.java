package com.example.monitor.test;

import com.example.monitor.DexPriceService;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class Test {
    private static final String DEFAULT_RPC_ENDPOINT = "https://bsc-mainnet.nodereal.io/v1/9613675572f24988819d24233504b290";


    public static void main(String[] args) throws IOException {
        Web3j web3j = Web3j.build(new HttpService(DEFAULT_RPC_ENDPOINT));
        Function function = new Function(
                "getPair",

                Arrays.asList(new Address(normalize("0x55d398326f99059ff775485246999027b3197955")), new Address(normalize("0xc12eFb9e4A1A753e7f6523482C569793C2271dbB"))),
                Collections.singletonList(new TypeReference<Address>() {
                })
        );
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, "0xcA143Ce32Fe78f1f7019d7d551a6402fC5350c73", encodedFunction);

        EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
        List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        String pairAddress = values.get(0).getValue().toString();
        System.out.println(pairAddress);
    }

    private static String normalize(String address) {
        return address == null ? null : address.toLowerCase();
    }




}
